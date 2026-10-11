package com.trace.app.vlm

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 屏幕采集：MediaProjection → VirtualDisplay → ImageReader。
 *
 * 关键设计：ImageReader 的监听器**只在有人等待时**才把这一帧转成 JPEG，
 * 其余时候直接丢帧，避免常驻 CPU 占用（实测 slice=9 时模型已吃掉 4 个核）。
 *
 * Android 14+ 的调用顺序硬约束：必须先在「mediaProjection 类型的前台服务」
 * 里 startForeground，再调用 getMediaProjection()，否则直接抛 SecurityException。
 */
object ScreenCapture {

    private const val TAG = "TraceCapture"

    /** 没人等待时，最多每 1.5 s 编码一帧留作兜底。 */
    private const val REFRESH_MS = 1_500L

    /** 小于这个体积的 JPEG 视为空帧（1216x2640 纯黑 ≈ 19.5 KB）。 */
    private const val BLANK_JPEG_BYTES = 30_000

    @Volatile private var projection: MediaProjection? = null
    @Volatile private var display: VirtualDisplay? = null
    @Volatile private var reader: ImageReader? = null
    private var thread: HandlerThread? = null

    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var density = 0

    @Volatile private var pending: CompletableFuture<ByteArray>? = null

    /** 本轮抓帧的截止时刻（uptimeMillis），监听器用它判断还有没有时间等下一帧。 */
    @Volatile private var pendingDeadline = 0L

    /** 本轮已经收到的「空帧」兜底——如果一直等不到正常帧，至少能拿它交差。 */
    @Volatile private var blankJpeg: ByteArray? = null

    /** nudge 之后的第一帧往往是空的（surface 重建瞬间的未初始化缓冲区），要丢掉。 */
    @Volatile private var skipNext = false

    /** 最近一次成功编码的帧，超时时作为兜底（可能略旧）。 */
    @Volatile private var lastJpeg: ByteArray? = null

    /** lastJpeg 的刷新时间，用于节流。 */
    @Volatile private var lastFrameAt = 0L

    @Volatile var running = false
        private set

    val sizeText: String get() = "${width}x${height}"

    fun start(ctx: Context, resultCode: Int, data: Intent): Boolean {
        stop()
        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return false
        val p = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            Log.e(TAG, "getMediaProjection failed", t)
            return false
        } ?: return false

        val metrics = ctx.resources.displayMetrics
        width = metrics.widthPixels
        height = metrics.heightPixels
        density = metrics.densityDpi

        val ht = HandlerThread("trace-capture").apply { start() }
        thread = ht
        val handler = Handler(ht.looper)

        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ ir -> onFrameAvailable(ir) }, handler)

        // Android 14+ 硬约束：必须先 registerCallback，再 createVirtualDisplay，
        // 否则抛 IllegalStateException("Must register a callback before starting capture")。
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "projection stopped by system")
                running = false
            }
        }, handler)

        val d = p.createVirtualDisplay(
            "trace-capture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler,
        )

        projection = p
        reader = r
        display = d
        running = true
        Log.i(TAG, "projection started $sizeText @$density")
        return true
    }

    fun stop() {
        running = false
        pending?.completeExceptionally(IllegalStateException("capture stopped"))
        pending = null
        skipNext = false
        try { display?.release() } catch (t: Throwable) { Log.w(TAG, "display release", t) }
        try { reader?.close() } catch (t: Throwable) { Log.w(TAG, "reader close", t) }
        try { projection?.stop() } catch (t: Throwable) { Log.w(TAG, "projection stop", t) }
        display = null
        reader = null
        projection = null
        thread?.quitSafely()
        thread = null
        lastJpeg = null
        blankJpeg = null
        skipNext = false
        lastFrameAt = 0L
    }

    /**
     * 抓一帧并编码成 JPEG。
     *
     * 静止画面不会产生新帧，所以先 nudge 一次强迫合成器重绘；但重绘后的
     * **第一帧**常常是未初始化的空缓冲区（实测 1216x2640 全黑，19568 B），
     * 因此丢掉第一帧再取下一帧；若下一帧还是空帧且还有时间就继续等，
     * 到点了就退回空帧/上一帧，保证调用方总能拿到东西。
     *
     * 失败或超时返回最近一帧（可能为 null）。
     */
    fun captureJpeg(timeoutMs: Long = 4_500L): ByteArray? {
        val r = reader ?: return lastJpeg
        if (!running) return lastJpeg

        val fut = CompletableFuture<ByteArray>()
        blankJpeg = null
        skipNext = true
        pendingDeadline = SystemClock.uptimeMillis() + timeoutMs
        pending = fut
        nudge(r)
        return try {
            fut.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            Log.w(TAG, "capture failed: ${t.message}")
            blankJpeg ?: lastJpeg
        } finally {
            pending = null
        }
    }

    /** 画面静止时不会产生新帧，重设一次 surface 强迫合成器重绘。 */
    private fun nudge(r: ImageReader) {
        val d = display ?: return
        try {
            d.surface = null
            d.surface = r.surface
        } catch (t: Throwable) {
            Log.w(TAG, "nudge failed", t)
        }
    }

    private fun onFrameAvailable(ir: ImageReader) {
        val image: Image = try {
            ir.acquireLatestImage()
        } catch (t: Throwable) {
            Log.w(TAG, "acquire image failed", t)
            return
        } ?: return

        try {
            val fut = pending
            if (fut == null) {
                // 没人在等：只在屏幕有动作时低频刷新一份兜底帧，其余帧直接丢（省 CPU）
                if (!skipNext && SystemClock.uptimeMillis() - lastFrameAt >= REFRESH_MS) {
                    val jpeg = imageToJpeg(image)
                    if (jpeg != null) {
                        lastFrameAt = SystemClock.uptimeMillis()
                        if (!looksBlank(jpeg)) lastJpeg = jpeg
                    }
                }
                return
            }

            if (skipNext) {
                skipNext = false
                Log.d(TAG, "丢弃 nudge 后的第一帧")
                return
            }

            val jpeg = imageToJpeg(image) ?: run {
                fut.completeExceptionally(IllegalStateException("JPEG 编码失败"))
                return
            }
            if (looksBlank(jpeg)) {
                blankJpeg = jpeg
                if (SystemClock.uptimeMillis() < pendingDeadline) {
                    Log.d(TAG, "空帧 ${jpeg.size}B，继续等下一帧")
                    return
                }
                Log.w(TAG, "等不到正常帧，只能用空帧 ${jpeg.size}B")
            }
            lastJpeg = jpeg
            lastFrameAt = SystemClock.uptimeMillis()
            fut.complete(jpeg)
        } catch (t: Throwable) {
            pending?.completeExceptionally(t)
        } finally {
            image.close()
        }
    }

    /**
     * 空帧判定：1216x2640 的纯黑 JPEG 只有 ~19.5 KB，真实界面（哪怕深色主题）
     * 因为有大字和控件边缘，通常 ≥ 40 KB。这里只用来「多等一帧」，判定错了
     * 也只是多花几秒，不会丢内容。
     */
    private fun looksBlank(jpeg: ByteArray): Boolean = jpeg.size < BLANK_JPEG_BYTES

    private fun imageToJpeg(image: Image): ByteArray? {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val paddedWidth = width + rowPadding / pixelStride
        val bmp = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(plane.buffer)

        val out = ByteArrayOutputStream()
        if (paddedWidth != width) {
            val cropped = Bitmap.createBitmap(bmp, 0, 0, width, height)
            cropped.compress(Bitmap.CompressFormat.JPEG, 90, out)
            cropped.recycle()
        } else {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        bmp.recycle()
        return out.toByteArray()
    }
}

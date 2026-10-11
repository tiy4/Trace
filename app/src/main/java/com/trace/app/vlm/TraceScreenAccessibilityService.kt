package com.trace.app.vlm

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 无障碍服务：Trace 的「整屏截图 API」+「界面语义读取器」。
 *
 * 1. 截屏： [takeScreenshot]（API 30+），整屏、开一次长期有效。
 *    对比 MediaProjection：每次进程重启都要重新点一遍系统授权弹窗，而且本机
 *    （vivo / Android 16）弹窗默认是「共享一个应用」，还得手动切「共享整个屏幕」，
 *    自动截屏没法无人值守。
 * 2. 语义：读当前窗口的 content-desc / text，拿点赞/收藏状态、视频文案、作者。
 *    实测抖音暂停时能精确读到「未点赞，喜欢2597，按钮」「未选中，收藏348，按钮」。
 *
 * 不做自动点击、不请求按键过滤。所有带阻塞的方法都**不能在主线程调用**
 * （回调跑在 mainExecutor 上，主线程会自己等自己）。
 */
class TraceScreenAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TraceA11y"

        /** 遍历节点的上限（时间上限先到就停）：抖音这种界面节点很多，防止一次遍历卡住。 */
        private const val MAX_NODES = 3_000

        /**
         * 单次读树的时间上限。实测抖音播放中每个节点要 0.7ms（静止时 0.18ms），
         * 200ms 只走到 285 个节点 → 语义树变「薄」；放宽到 500ms，代价 ~1% CPU。
         */
        private const val MAX_PROBE_MS = 1_500L

        /**
         * 「在干什么」采样用的轻量读法：只想要一个标题/作者/几个关键词，
         * 每换一个页面都会读一次，所以上限卡得比内容通道紧得多（500 节点 / 400ms）。
         */
        private const val LITE_MAX_NODES = 500
        private const val LITE_MAX_PROBE_MS = 400L

        @Volatile var probeCount = 0
            private set
        @Volatile var shotCount = 0
            private set

        /** 最近一次截屏失败的错误码（0 = 上次成功）。诊断用，会显示在 App 状态里。 */
        @Volatile var lastShotError = 0
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        A11yCapture.attach(this)
        Log.i(TAG, "无障碍服务已连接（截屏 + 语义读取）")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不处理事件流：语义靠主动读树（事件在视频播放时会疯狂触发，反而更难控）。
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        A11yCapture.detach(this)
        Log.i(TAG, "无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        A11yCapture.detach(this)
        super.onDestroy()
    }

    /** 当前活动窗口属于哪个应用（一次 binder 调用，不做遍历）。 */
    fun currentPkg(): String? = try {
        rootInActiveWindow?.packageName?.toString()
    } catch (t: Throwable) {
        null
    }

    /**
     * 读一次界面语义快照。读不到返回 null。
     * 遍历有上限，超出的节点直接放弃（宁可少读，不要卡）。
     */
    fun readProbe(): ScreenProbe? = readTree(MAX_NODES, MAX_PROBE_MS)

    /**
     * 轻量读一次（500 节点 / 400ms）。给「在干什么」这类高频、只要个标题的场景用。
     */
    fun readProbeLite(): ScreenProbe? = readTree(LITE_MAX_NODES, LITE_MAX_PROBE_MS)

    private fun readTree(maxNodes: Int, maxMs: Long): ScreenProbe? {
        val root = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            Log.w(TAG, "rootInActiveWindow failed", t)
            null
        } ?: return null

        val texts = ArrayList<String>(64)
        val descs = ArrayList<String>(64)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        var truncated = false
        val t0 = SystemClock.uptimeMillis()
        while (queue.isNotEmpty()) {
            if (visited >= maxNodes || SystemClock.uptimeMillis() - t0 > maxMs) {
                truncated = true
                break
            }
            val n = queue.removeFirst()
            visited++
            try {
                val t = n.text?.toString()?.trim()
                if (!t.isNullOrEmpty() && t.length <= 300) texts.add(t)
                val d = n.contentDescription?.toString()?.trim()
                if (!d.isNullOrEmpty() && d.length <= 300) descs.add(d)
                for (i in 0 until n.childCount) {
                    val c = n.getChild(i) ?: continue
                    queue.addLast(c)
                }
            } catch (t: Throwable) {
                // 单个节点失效（窗口正在销毁）就跳过，别让整次读取失败
            }
        }
        probeCount++
        return ScreenProbe(
            root.packageName?.toString() ?: "",
            texts,
            descs,
            System.currentTimeMillis(),
            visited,
            truncated,
        )
    }

    /** 截整屏并编码成 JPEG；拿不到就返回 null。 */
    @RequiresApi(Build.VERSION_CODES.R)
    fun shootJpeg(timeoutMs: Long = 6_000L): ByteArray? = withScreenshot(timeoutMs) { bmp ->
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        out.toByteArray()
    }

    /**
     * 截一帧并压成 [FrameWatcher.W]x[FrameWatcher.H] 灰度指纹。
     * 只用于「画面变没变」的比较，不落盘、不编码 JPEG —— 比截 JPEG 便宜一个数量级。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun shootFingerprint(timeoutMs: Long = 4_000L): ByteArray? = withScreenshot(timeoutMs) { bmp ->
        fingerprint(bmp)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun <T> withScreenshot(timeoutMs: Long, transform: (Bitmap) -> T?): T? {
        var attempt = 0
        while (true) {
            var errCode = -1
            val fut = CompletableFuture<T?>()
            val cb = object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    var out: T? = null
                    try {
                        val hardware = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                        val soft = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                        hardware?.recycle()
                        if (soft != null) out = transform(soft)
                    } catch (t: Throwable) {
                        Log.w(TAG, "convert screenshot failed", t)
                    } finally {
                        try { hb.close() } catch (t: Throwable) { Log.w(TAG, "close buffer", t) }
                    }
                    shotCount++
                    lastShotError = 0
                    fut.complete(out)
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "takeScreenshot failed code=$errorCode")
                    errCode = errorCode
                    lastShotError = errorCode
                    fut.complete(null)
                }
            }
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, cb)
            } catch (t: Throwable) {
                Log.w(TAG, "takeScreenshot threw", t)
                fut.complete(null)
            }
            val out = try {
                fut.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (t: Throwable) {
                Log.w(TAG, "takeScreenshot timeout: ${t.message}")
                null
            }
            if (out != null) return out
            // 错误码 3 = ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT：同一个采样周期里
            // 先取指纹、紧接着又截 JPEG 就会撞上（实测抖音换视频时必现）。
            // 系统的最小间隔很短，退让一下重试一次就够了。
            if (errCode == 3 && attempt == 0) {
                attempt++
                try { Thread.sleep(900L) } catch (t: InterruptedException) { /* ignore */ }
                continue
            }
            return null
        }
    }

    private fun fingerprint(src: Bitmap): ByteArray {
        val w = FrameWatcher.W
        val h = FrameWatcher.H
        val small = if (src.width == w && src.height == h) src
        else Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()
        src.recycle()
        val out = ByteArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val lum = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            out[i] = lum.toByte()
        }
        return out
    }
}
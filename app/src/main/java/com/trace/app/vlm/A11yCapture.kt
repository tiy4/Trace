package com.trace.app.vlm

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 无障碍能力的门面。
 *
 * 采集源优先级：**无障碍可用就用无障碍**（无需授权弹窗、整屏、长期有效），
 * 否则退回 MediaProjection（要用户点授权）。
 */
object A11yCapture {

    private const val TAG = "TraceA11y"

    @Volatile private var svc: TraceScreenAccessibilityService? = null

    internal fun attach(s: TraceScreenAccessibilityService) { svc = s }

    internal fun detach(s: TraceScreenAccessibilityService) {
        if (svc === s) svc = null
    }

    /** 服务此刻是否已连上（截屏随时可用）。 */
    val ready: Boolean get() = svc != null

    /** 是否已在系统设置里启用（此刻没连上也可能返回 true）。 */
    fun isEnabled(ctx: Context): Boolean {
        val flat = try {
            Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        } catch (t: Throwable) {
            null
        } ?: return false
        val cn = ComponentName(ctx, TraceScreenAccessibilityService::class.java)
        val full = cn.flattenToString()
        val short = cn.flattenToShortString()
        return flat.split(':').any { it.isNotBlank() && (it.equals(full, true) || it.equals(short, true)) }
    }

    /**
     * 无障碍截一帧；不可用时返回 null（调用方回退投屏）。
     * 超时给得宽（12 s）：实测模型在跑（CPU 420%）时系统截屏回调会明显变慢。
     */
    fun captureJpeg(timeoutMs: Long = 12_000L): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val s = svc ?: return null
        return try {
            s.shootJpeg(timeoutMs)
        } catch (t: Throwable) {
            Log.w(TAG, "a11y capture failed", t)
            null
        }
    }

    /** 截一帧灰度指纹（16x16），只用于画面变化比较。 */
    fun captureFingerprint(timeoutMs: Long = 4_000L): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val s = svc ?: return null
        return try {
            s.shootFingerprint(timeoutMs)
        } catch (t: Throwable) {
            Log.w(TAG, "a11y fingerprint failed", t)
            null
        }
    }

    /** 当前活动窗口的包名（便宜，不遍历）。 */
    fun currentPkg(): String? = svc?.let {
        try {
            it.currentPkg()
        } catch (t: Throwable) {
            null
        }
    }

    /** 最近一次截屏失败的错误码（0 = 上次成功，-1 = 无障碍服务没连上）。 */
    val lastShotError: Int
        get() = if (svc != null) TraceScreenAccessibilityService.lastShotError else -1

    /** 读一次界面语义快照（点赞/收藏状态、标题、作者）。 */
    fun readProbe(): ScreenProbe? = svc?.let {
        try {
            it.readProbe()
        } catch (t: Throwable) {
            Log.w(TAG, "a11y readProbe failed", t)
            null
        }
    }

    /**
     * 轻量读一次（500 节点 / 400ms）：「在干什么」采样用。
     * 比 [readProbe] 便宜一个量级，代价是节点的深部可能读不到。
     */
    fun readProbeLite(): ScreenProbe? = svc?.let {
        try {
            it.readProbeLite()
        } catch (t: Throwable) {
            Log.w(TAG, "a11y readProbeLite failed", t)
            null
        }
    }
}
package com.trace.app.vlm

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
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
        val listed = flat.split(':').any { it.isNotBlank() && (it.equals(full, true) || it.equals(short, true)) }
        // 光在名单里不够：总开关 ACCESSIBILITY_ENABLED 也可能是 0（实测 vivo 会留着名单关总闸），
        // 这时候服务其实没在跑，必须算「未启用」，否则自愈会被这个提前返回挡住。
        return listed && masterEnabled(ctx)
    }

    /** 无障碍总开关。 */
    private fun masterEnabled(ctx: Context): Boolean = try {
        Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
    } catch (t: Throwable) {
        false
    }

    /**
     * 是否拿到「自己把无障碍写回去」的权限。
     *
     * `WRITE_SECURE_SETTINGS` 是 signature|development 级权限，普通安装拿不到，
     * 但 adb 一次性 `pm grant` 就能给（scripts\enable-a11y.ps1 已经带上）。
     */
    fun canSelfHeal(ctx: Context): Boolean = try {
        ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    /**
     * 发现无障碍被系统摘掉时，自己把它写回去。
     *
     * 为什么需要：OriginOS 会在后台回收第三方无障碍服务（实测闲置约两小时后
     * `enabled_accessibility_services` 直接变空），用户看到的现象就是
     * 「我明明点开了，一出来又自动关了」。写这个系统设置的正常途径是 adb，
     * 但只要 App 拿到了 [canSelfHeal]，就能在服务里自己补回来——
     * 系统监听这个 setting，写进去的瞬间就会重新绑定服务，不用用户再点。
     *
     * 只动自己那一项：系统里别人（vivo 自带、其它 App）已启用的服务原样保留。
     */
    fun ensureEnabled(ctx: Context): Boolean {
        if (isEnabled(ctx)) return true
        return try {
            val cn = ComponentName(ctx, TraceScreenAccessibilityService::class.java)
            val full = cn.flattenToString()
            val short = cn.flattenToShortString()
            val cur = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            val list = cur.split(':').filter { it.isNotBlank() && !it.equals(full, true) && !it.equals(short, true) }
            val next = (list + full).joinToString(":")
            Settings.Secure.putString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                next,
            )
            Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "无障碍被系统摘掉，已自行写回设置：$next")
            true
        } catch (t: Throwable) {
            // SecurityException = 没被 adb 授权过，这时只能请用户手动开。
            Log.w(TAG, "无障碍自愈失败：${t.javaClass.simpleName} ${t.message}")
            false
        }
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
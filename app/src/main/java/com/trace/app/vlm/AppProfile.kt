package com.trace.app.vlm

/**
 * 「什么时候值得截一帧送去分析」按应用分档。
 *
 * 为什么要分档（实测 2026-10-07）：
 *  - 内容流应用（抖音等）在系统侧**看不到应用内切换**：推荐流里
 *    topResumedActivity 恒为 com.ss.android.ugc.aweme/.splash.SplashActivity，
 *    滑动换视频不产生任何 ACTIVITY_RESUMED 事件 —— 靠 UsageStats 的 dwell 永远只能
 *    看到「用户打开了抖音」这一次。它们必须靠「内容变了」：无障碍语义变化（标题/
 *    文案变了）或画面指纹变化。
 *  - 普通应用（设置、浏览器、购物）没有这种高频内容切换，dwell 触发就够，也更省。
 *  - 私密/支付类永不自动截。
 */
enum class TriggerMode {
    /** 只在「用户停在这个应用里 ≥ dwell」时截一次（默认，最省）。 */
    DWELL,

    /** 只在「应用内内容发生切换」时截（内容流）。 */
    CONTENT,

    /** 停留够久 **且** 内容切换过，两者都要。 */
    BOTH,

    /** 这个应用永不自动截（私密 / 支付）。 */
    NEVER,
}

data class AppProfile(
    val pkg: String,
    val mode: TriggerMode,
    /** 同一应用两次自动分析的最小间隔（内容流可短，聊天/工具类要长）。 */
    val minIntervalMs: Long,
    val label: String = "",
)

object AppProfiles {

    /** 内容流：每条内容最短 30 s 才有一次模型分析（模型单次 15-20 s，这是硬约束）。 */
    private const val STREAM_MS = 30_000L

    private val table: Map<String, AppProfile> = listOf(
        AppProfile("com.ss.android.ugc.aweme", TriggerMode.CONTENT, STREAM_MS, "抖音"),
        AppProfile("com.ss.android.ugc.aweme.lite", TriggerMode.CONTENT, STREAM_MS, "抖音极速版"),
        AppProfile("com.ss.android.ugc.live", TriggerMode.CONTENT, STREAM_MS, "抖音直播"),
        AppProfile("com.smile.gifmaker", TriggerMode.CONTENT, STREAM_MS, "快手"),
        AppProfile("com.kuaishou.nebula", TriggerMode.CONTENT, STREAM_MS, "快手极速版"),
        AppProfile("com.xingin.xhs", TriggerMode.CONTENT, STREAM_MS, "小红书"),
        AppProfile("tv.danmaku.bili", TriggerMode.CONTENT, STREAM_MS, "哔哩哔哩"),
        AppProfile("com.ss.android.article.news", TriggerMode.CONTENT, STREAM_MS, "今日头条"),
        AppProfile("com.tencent.qqlive", TriggerMode.CONTENT, STREAM_MS, "腾讯视频"),
        AppProfile("com.youku.phone", TriggerMode.CONTENT, STREAM_MS, "优酷"),
        AppProfile("com.qiyi.video", TriggerMode.CONTENT, STREAM_MS, "爱奇艺"),
        // 私密 / 支付：永不自动截（而且支付页一般有 FLAG_SECURE，本来也截不到）
        AppProfile("com.eg.android.AlipayGphone", TriggerMode.NEVER, 0L, "支付宝"),
        AppProfile("com.unionpay", TriggerMode.NEVER, 0L, "云闪付"),
        AppProfile("com.android.settings", TriggerMode.BOTH, 60_000L, "设置"),
        AppProfile("com.tencent.mm", TriggerMode.DWELL, 300_000L, "微信"),
        AppProfile("com.android.bbkcalculator", TriggerMode.BOTH, 30_000L, "计算器"),
    ).associateBy { it.pkg }

    private val DEFAULT = AppProfile("", TriggerMode.DWELL, 60_000L, "")

    fun of(pkg: String?): AppProfile = table[pkg] ?: DEFAULT

    /** 是否属于「内容流」——这类应用会走无障碍语义 + 画面指纹两条触发路径。 */
    fun isStream(pkg: String?): Boolean = of(pkg).mode == TriggerMode.CONTENT
}
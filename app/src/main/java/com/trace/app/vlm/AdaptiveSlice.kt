package com.trace.app.vlm

/**
 * 切片自适应策略。
 *
 * 真机实测（1216×2640 截图、thinking 关闭，见 Step3-report.md）：
 *   slice=1 → 视觉预填充 7.1 s
 *   slice=3 → 11.4 s
 *   slice=5 → 20.4 s
 *   slice=9 → 37.5 s
 * 文本生成阶段与切片基本无关（1.4–3.4 s），所以切片只买「读屏细节」。
 *
 * 策略：按这次截屏的用途定档，并允许上一次的结果做一次反馈修正——
 * 若同包上一次回答过短或压根没有（首次见到这个应用），本次用 [USER] 档。
 *
 * 为什么「首次」不能省成 1 片：真机实测 slice=1 时整屏被压成一格，
 * 小字直接糊掉——设置页的 Wi-Fi 名与账号名都被读错（"ChinatNet.-Dry v 5"）。
 * 1 片只够看版面，不够读内容；而 Trace 的价值恰好在「读出关键数字/文字」。
 */
object AdaptiveSlice {

    /** 后台事件触发（已认识的应用）：最省。 */
    const val EVENT = 1

    /** 用户主动看一眼 / 首次见到某个应用。 */
    const val USER = 3

    /** 用户要求看清楚细节。 */
    const val DETAIL = 5

    const val MAX = 9

    /** 短于此长度的回答视为「没看清」。 */
    const val SHORT_ANSWER = 60

    fun forEvent(lastAnswerForPkg: String?): Int {
        val prev = lastAnswerForPkg?.trim().orEmpty()
        // 首次（prev 为空）或上次没看清（回答过短）→ 升到 3 片。
        return if (prev.length < SHORT_ANSWER) USER else EVENT
    }

    fun clamp(n: Int): Int = when {
        n < 1 -> 1
        n > MAX -> MAX
        else -> n
    }
}

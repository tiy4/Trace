package com.trace.app.vlm

/**
 * 画面变化触发器（视觉路径，语义路径拿不到树时的兜底）。
 *
 * 做法：整屏缩到 [W]x[H] 灰度当指纹，与「参考帧」比平均绝对亮度差。
 * 判定「内容切换了」需要连续 [confirmSamples] 次采样都超过阈值 —— 单帧抖动不算。
 *
 * 已知局限（诚实记录）：画面全在动时（视频播放中）整帧差异本来就大，
 * 这条路径可能把「同一个视频在播」误判成「换了内容」。所以内容流应用优先走
 * [ScreenProbe.contentKey] 的语义路径；本类只用于语义树不可用的应用，
 * 或者灰度指纹**外圈**（状态栏/底部导航/侧栏）这种 UI 骨架对比。
 */
class FrameWatcher(
    private val changeThreshold: Double = 12.0,
    private val confirmSamples: Int = 2,
    /** 只比这些格子（外圈 UI 骨架），null = 全帧。 */
    private val mask: BooleanArray? = null,
) {
    private var ref: ByteArray? = null
    private var streak = 0

    var lastDiff = 0.0
        private set

    /** @return true = 确认「内容切换了」（每次切换只报一次） */
    fun feed(fp: ByteArray): Boolean {
        val r = ref
        if (r == null) {
            ref = fp
            streak = 0
            return false
        }
        lastDiff = meanAbsDiff(r, fp, mask)
        if (lastDiff < changeThreshold) {
            // 画面稳定：参考帧跟随（避免缓慢变化累积成假切换）
            ref = fp
            streak = 0
            return false
        }
        streak += 1
        if (streak < confirmSamples) return false
        ref = fp
        streak = 0
        return true
    }

    fun reset(fp: ByteArray? = null) {
        ref = fp
        streak = 0
        lastDiff = 0.0
    }

    companion object {
        const val W = 16
        const val H = 16

        /** 只看外圈两排两列（状态栏、底部导航、左右侧栏），中间的视频区不参与。 */
        fun edgeMask(): BooleanArray {
            val m = BooleanArray(W * H)
            for (y in 0 until H) for (x in 0 until W) {
                val edge = y < 3 || y >= H - 3 || x < 2 || x >= W - 2
                m[y * W + x] = edge
            }
            return m
        }

        fun meanAbsDiff(a: ByteArray, b: ByteArray, mask: BooleanArray? = null): Double {
            if (a.size != b.size) return 255.0
            var sum = 0L
            var n = 0
            for (i in a.indices) {
                if (mask != null && !mask[i % mask.size]) continue
                val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                sum += if (d < 0) -d else d
                n++
            }
            return if (n == 0) 0.0 else sum.toDouble() / n
        }
    }
}
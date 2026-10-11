package com.trace.app.vlm

/**
 * 一帧「无障碍语义快照」。
 *
 * 实测（2026-10-07，抖音推荐流暂停时的一次 uiautomator dump）：
 *   content-desc: 未点赞，喜欢2597，按钮 / 未选中，收藏348，按钮 / 评论40，按钮
 *                 / 分享190，按钮 / 音乐，@卢骁的急诊日记创作的原声，按钮
 *   text        : @卢骁的急诊日记 / 一张照片就给自己下诊断？这些问题别提前焦虑了…
 * 而 resource-id 大部分是混淆的（w0q / b1g / i6 …），所以一律走文本，不看 id。
 *
 * 这份快照的价值：点赞/收藏是**精确**的（有状态文字），标题/作者是**免费**的（不用跑模型）。
 */
data class ScreenProbe(
    val pkg: String,
    val texts: List<String>,
    val descs: List<String>,
    val ts: Long,
    /** 这次遍历实际读到的节点数（诊断用）。 */
    val nodes: Int = 0,
    /** 是否撞到了节点数 / 时间上限（true 说明树还有没读到的部分）。 */
    val truncated: Boolean = false,
) {
    /** 「计数类」desc：喜欢 / 评论 / 收藏 / 分享 —— 换一条内容必然变。 */
    val countDescs: List<String>
        get() = descs
            .filter { it.contains("，") && COUNT_KEYS.any { k -> it.contains(k) } }
            .sorted()

    /**
     * 这棵语义树是不是「薄」的：没有点赞/收藏状态、也没有任何计数。
     *
     * 实测（2026-10-10，抖音推荐流播放中）：读到的树只有「关注」一个 desc，
     * 早前静态 dump 里存在的「未点赞，喜欢2597，按钮」全都不见了 —— 窗口一直不
     * idle，无障碍树在播放动画时读不全。这种情况必须退回画面指纹，否则换视频
     * 永远检测不到。
     */
    val isThin: Boolean
        get() = liked == null && saved == null && countDescs.isEmpty()

    /** 内容标题：抖音里正文在 uid=desc 的节点上，这里取最长的一条文本。 */
    val title: String
        get() = texts.maxByOrNull { it.length }?.take(200) ?: ""

    /** 作者（@xxx）。 */
    val author: String
        get() = (texts + descs).firstOrNull { it.trim().startsWith("@") }?.trim()?.take(60)
            ?: descs.firstOrNull { it.trim().startsWith("@") }?.trim()?.take(60)
            ?: ""

    /**
     * 点赞状态：false=未点赞，true=已点赞，null=这个界面没有点赞按钮。
     *
     * 注意：抖音的树里**同时挂着好几支视频的按钮**（当前 + 相邻预加载页），实测一次
     * 读到三个：「未点赞，喜欢2506」「未点赞，喜欢1.9万」「未点赞，喜欢12」。所以
     * 单看第一条毫无意义——判定「用户点了赞」要看**有几条处于已赞态**（见 likedCount）。
     */
    val liked: Boolean? get() = likeStates.firstOrNull()

    /** 收藏状态：false=未收藏，true=已收藏，null=没有收藏按钮。 */
    val saved: Boolean? get() = saveStates.firstOrNull()

    /** 这一屏里所有点赞按钮的状态（含相邻预加载页）。 */
    val likeStates: List<Boolean> get() = statesOf("点赞")

    val saveStates: List<Boolean> get() = statesOf("收藏")

    /** 处于「已赞」态的按钮个数——点赞动作会让它 +1，取消让它 -1。 */
    val likedCount: Int get() = likeStates.count { it }

    val savedCount: Int get() = saveStates.count { it }

    /**
     * 点赞按钮的 (计数, 是否已赞)。
     *
     * 用途：区分「用户按了赞」和「画面换了一页（已赞的那条滑走了）」——
     * 前者一定伴随同一个数字的翻转（喜欢12 → 已点赞，喜欢13），后者不会。
     */
    val likePairs: List<Pair<Long, Boolean>> get() = pairsOf("点赞")

    val savePairs: List<Pair<Long, Boolean>> get() = pairsOf("收藏")

    private fun pairsOf(key: String): List<Pair<Long, Boolean>> {
        val out = ArrayList<Pair<Long, Boolean>>(4)
        for (s in descs + texts) {
            if (!s.contains(key)) continue
            val v = when {
                s.contains("未") -> false
                s.contains("取消") || s.contains("已") -> true
                else -> null
            } ?: continue
            val n = parseCount(s)
            if (n >= 0) out.add(n to v)
        }
        return out
    }

    /** 从「未点赞，喜欢2506，按钮」抠出 2506；「喜欢1.9万」→ 19000；抠不到 -1。 */
    private fun parseCount(s: String): Long {
        val m = COUNT_RE.find(s) ?: return -1L
        val base = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return -1L
        val mul = when (m.groupValues[2]) {
            "万" -> 10_000L
            "千" -> 1_000L
            "亿" -> 100_000_000L
            else -> 1L
        }
        return (base * mul).toLong()
    }

    private fun statesOf(key: String): List<Boolean> {
        val out = ArrayList<Boolean>(4)
        for (s in descs + texts) {
            if (!s.contains(key)) continue
            // 抖音的状态词是混着写的：点赞是「未点赞，喜欢2597，按钮」，
            // 收藏是「未选中，收藏348，按钮」——所以不能只认「未$key」。
            val v = when {
                s.contains("未") -> false
                s.contains("取消") || s.contains("已") -> true
                else -> null    // 只有计数、没有状态词，判不了，继续找
            } ?: continue
            out.add(v)
        }
        return out
    }

    /**
     * 内容指纹：标题 + 作者 + 「喜欢/评论/收藏/分享」这几个计数。
     * 换一个视频，标题和计数一定变；视频本身在播、在动，也不会影响它。
     * （画面指纹做不到这一点——这是语义路径比视觉路径更准的原因。）
     */
    /**
     * 内容指纹：**只认标题 + 作者**。
     *
     * 2026-10-11 00:10 实测（抖音）：早先版本把「喜欢/评论/收藏/分享」计数也算进键里，
     * 同一支视频每 2–7 s 就产生一条 VIDEO_CHANGED —— 计数本身在跳（别人点赞/取消赞），
     * 跟「换了一条内容」无关。计数只适合展示，不适合当键。
     */
    fun contentKey(): String = (title + "|" + author).take(400)

    /** 供落库/日志用的一行摘要。 */
    fun summary(): String {
        val flag = if (truncated) "+" else ""
        val kind = if (isThin) "薄" else "富"
        return "$kind n=$nodes$flag key=${contentKey().take(60)} " +
            "like=${liked}/$likedCount save=${saved}/$savedCount"
    }

    companion object {
        private val COUNT_KEYS = listOf("喜欢", "评论", "收藏", "分享", "点赞")

        /** 抠 desc 里的第一个数字：`未点赞，喜欢2506，按钮` → 2506（单位万/千/亿 换算）。 */
        private val COUNT_RE = Regex("""([0-9]+(?:\.[0-9]+)?)\s*(万|千|亿)?""")
    }
}
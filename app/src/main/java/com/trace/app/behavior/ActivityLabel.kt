package com.trace.app.behavior

import com.trace.app.vlm.ScreenProbe

/**
 * 「用户在这个应用里干什么」——把不可读的 Activity 类名 + 语义快照翻成中文短语。
 *
 * 数据来源两条，都不用额外权限、不跑模型：
 *   1. **Activity 类名**：MonitorService 每 15 s 从 UsageStats 抄一份 RESUMED 事件，
 *      `usage_events.activity` 里存的就是全类名（`com.ss.android.ugc.aweme.detail.ui.DetailActivity`）。
 *      → 历史记录也能**零成本回溯**翻译，不用重装采集。
 *   2. **无障碍语义快照**（可选）：`ScreenProbe.title` / `author` / `descs`。
 *      有它就能细到「在刷视频 · @某某」；没有它只到「在刷视频」。
 *
 * 判定顺序：先应用专属规则（抖音/微信/小红书这类高频应用，规则最准），
 * 再通用关键词（Activity 类名里带 Detail / Search / Chat 之类，跨应用都成立）。
 */
object ActivityLabel {

    /** 描述一行「在干什么」。`activity` 用全类名或短类名都行。 */
    fun describe(pkg: String?, activity: String?, probe: ScreenProbe? = null): String {
        val cls = simple(activity)
        val key = cls.lowercase()
        val p = pkg.orEmpty().lowercase()

        // ---------- 1. 高频应用专属 ----------
        if (p.startsWith("com.ss.android.ugc.aweme") || p.startsWith("com.ss.android.ugc.live") ||
            p.startsWith("com.smile.gifmaker") || p.startsWith("com.kuaishou")
        ) {
            appRule(key, probe,
                "live" to "看直播", "follow" to "看关注", "friend" to "看朋友",
                "search" to "搜索视频", "message" to "看私信", "profile" to "看个人主页",
                "user" to "看主页", "comment" to "看评论", "nearby" to "看同城",
                "publish" to "拍视频", "record" to "拍视频", "draft" to "看草稿箱",
                "setting" to "设置", "music" to "听音乐", "notice" to "看通知",
            )?.let { return it }
            return "在刷视频"
        }
        if (p.startsWith("com.tencent.mm")) {
            appRule(key, probe,
                "chatting" to "聊天", "sns" to "看朋友圈", "finder" to "看视频号",
                "mall" to "微信购物", "webview" to "看公众号文章", "appbrand" to "用小程序",
                "scan" to "扫一扫", "setting" to "设置", "address" to "通讯录",
                "search" to "搜索", "emoji" to "看表情", "pay" to "收付款",
                "game" to "玩游戏", "card" to "看卡包", "favorite" to "看收藏",
            )?.let { return it }
            return "在微信里"
        }
        if (p.startsWith("com.xingin.xhs")) {
            appRule(key, probe,
                "notedetail" to "看笔记", "search" to "搜笔记", "comment" to "看评论",
                "profile" to "看主页", "publish" to "发布笔记", "mall" to "逛商城",
                "message" to "看消息", "setting" to "设置",
            )?.let { return it }
            return "在刷笔记"
        }
        if (p.startsWith("tv.danmaku.bili") || p.startsWith("com.bilibili")) {
            appRule(key, probe,
                "videodetail" to "看视频", "bangumi" to "追番", "live" to "看直播",
                "search" to "搜索", "dynamic" to "看动态", "message" to "看消息",
                "setting" to "设置", "player" to "看视频",
            )?.let { return it }
            return "在逛 B 站"
        }
        if (p.startsWith("com.taobao") || p.startsWith("com.tmall") || p.startsWith("com.jingdong") ||
            p.startsWith("com.xunmeng.pinduoduo")
        ) {
            appRule(key, probe,
                "detail" to "看商品", "cart" to "看购物车", "order" to "看订单",
                "search" to "搜索商品", "pay" to "付款", "address" to "改收货地址",
                "logistics" to "看物流", "comment" to "看评价", "shop" to "逛店铺",
                "wallet" to "看钱包",
            )?.let { return it }
            return "在逛商城"
        }
        if (p.startsWith("com.sina.weibo")) {
            appRule(key, probe,
                "detail" to "看微博", "search" to "看热搜", "message" to "看消息",
                "comment" to "看评论", "profile" to "看主页", "publish" to "发微博",
            )?.let { return it }
            return "在刷微博"
        }
        if (p.startsWith("com.zhihu.android")) {
            appRule(key, probe,
                "answer" to "看回答", "question" to "看问题", "search" to "搜索",
                "comment" to "看评论", "profile" to "看主页", "message" to "看消息",
            )?.let { return it }
            return "在刷知乎"
        }
        if (p.startsWith("com.ss.android.article")) {
            appRule(key, probe,
                "search" to "搜索", "comment" to "看评论", "detail" to "看文章",
                "profile" to "看主页", "video" to "看视频",
            )?.let { return it }
            return "在刷头条"
        }

        // ---------- 2. 通用关键词（跨应用） ----------
        GENERIC.forEach { (k, v) -> if (key.contains(k)) return v }

        // ---------- 3. 读不出来的：别给用户看类名 ----------
        if (probe != null && probe.title.isNotBlank()) return "在浏览内容"
        return "使用中"
    }

    /** 明细：当前页面在讲什么（标题/作者），没有就返回空串。 */
    fun detail(probe: ScreenProbe?): String {
        if (probe == null) return ""
        val parts = ArrayList<String>(2)
        val author = probe.author
        if (looksMeaningful(author)) parts.add(author)
        val title = probe.title
        if (looksMeaningful(title) && title != author) parts.add(title.take(48))
        return parts.joinToString(" · ")
    }

    /**
     * `ScreenProbe.title` 取的是「最长的一段文本」，而屏幕上最长的那段经常是计数器、
     * 时间戳、剩余次数之类的噪声——实测抖音那行 detail 就是孤零零一个「17」。
     * 只留下≥4 个字、且含中文/字母的字符串（纯数字、纯符号一律丢掉）。
     */
    private fun looksMeaningful(s: String?): Boolean {
        val t = s?.trim().orEmpty()
        if (t.length < 4) return false
        return t.any { it.isLetter() }
    }

    /** 详情一行里更短的一版（列表里用）。 */
    fun shortDetail(probe: ScreenProbe?): String {
        val d = detail(probe)
        return if (d.length <= 28) d else d.take(28) + "…"
    }

    private fun simple(activity: String?): String =
        activity.orEmpty().substringAfterLast('.').substringAfterLast('$')

    /** 应用专属规则：返回第一条命中的；都没命中返回 null（交给该应用的兜底短语）。 */
    private fun appRule(key: String, @Suppress("UNUSED_PARAMETER") probe: ScreenProbe?, vararg rules: Pair<String, String>): String? {
        for ((k, v) in rules) if (key.contains(k)) return v
        return null
    }

    /** 通用 Activity 类名关键词 → 动作。顺序有意义：先具体，后笼统。 */
    private val GENERIC: List<Pair<String, String>> = listOf(
        "splash" to "启动",
        "login" to "登录",
        "register" to "注册",
        "scan" to "扫码",
        "pay" to "付款",
        "transfer" to "转账",
        "bill" to "看账单",
        "cart" to "看购物车",
        "order" to "看订单",
        "comment" to "看评论",
        "chat" to "聊天",
        "message" to "看消息",
        "imui" to "聊天",
        "search" to "搜索",
        "player" to "看视频",
        "video" to "看视频",
        "live" to "看直播",
        "music" to "听音乐",
        "reader" to "看小说",
        "book" to "看书",
        "album" to "看相册",
        "gallery" to "看相册",
        "camera" to "拍照",
        "record" to "录音",
        "map" to "看地图",
        "navigate" to "导航",
        "setting" to "设置",
        "profile" to "看个人主页",
        "user" to "看个人主页",
        "friend" to "看好友",
        "notice" to "看通知",
        "webview" to "看网页",
        "browser" to "浏览网页",
        "detail" to "看详情",
        "feed" to "刷内容流",
        "main" to "用主页",
        "home" to "用主页",
        "launcher" to "回到桌面",
    )
}

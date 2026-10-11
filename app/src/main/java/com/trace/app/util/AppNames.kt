package com.trace.app.util

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * 包名 → 中文应用名。
 *
 * 为什么要它：行为流里原本显示的是 `pkg.substringAfterLast('.')` —— 抖音是 `aweme`、
 * 淘宝是 `taobao`、更糟的是 `android` / `app` / `mall` / `lite` 这种一眼看不出是什么的尾巴。
 *
 * 取名的优先级：
 *   1. 系统 `PackageManager.getApplicationLabel`（最准，跟系统设置里显示的一致，含中文名）
 *   2. 内置兜底表（PackageManager 查不到时用：已卸载、多用户、系统包被隐藏）
 *   3. 包名末段（最后的兜底，至少有信息量）
 *
 * 结果做缓存：列表滚动时一次要解析几十次，PackageManager 调用不是免费的。
 * 线程安全：ConcurrentHashMap + PackageManager 只读查询，可从任意线程调用。
 */
object AppNames {

    private val cache = ConcurrentHashMap<String, String>()

    /** 常见应用兜底表（key 用小写包名；查表前先把包名转小写）。 */
    private val FALLBACK: Map<String, String> = mapOf(
        // 短视频 / 内容
        "com.ss.android.ugc.aweme" to "抖音",
        "com.ss.android.ugc.aweme.lite" to "抖音极速版",
        "com.ss.android.ugc.live" to "抖音直播",
        "com.smile.gifmaker" to "快手",
        "com.kuaishou.nebula" to "快手极速版",
        "com.xingin.xhs" to "小红书",
        "tv.danmaku.bili" to "哔哩哔哩",
        "com.ss.android.article.news" to "今日头条",
        "com.ss.android.article.lite" to "头条极速版",
        "com.tencent.qqlive" to "腾讯视频",
        "com.qiyi.video" to "爱奇艺",
        "com.youku.phone" to "优酷",
        "com.zhihu.android" to "知乎",
        "com.sina.weibo" to "微博",
        "com.tencent.weishi" to "微视",
        "com.netease.cloudmusic" to "网易云音乐",
        "com.tencent.qqmusic" to "QQ音乐",
        "cn.kuwo.player" to "酷我音乐",
        "com.kugou.android" to "酷狗音乐",
        "com.ximalaya.ting.android" to "喜马拉雅",
        "com.dragon.read" to "番茄小说",
        "com.qidian.QDReader" to "起点读书",
        "com.tencent.weread" to "微信读书",
        "com.duokan.reader" to "多看阅读",
        "com.tencent.qq" to "QQ",
        "com.tencent.mobileqq" to "QQ",
        // 社交 / 通讯
        "com.tencent.mm" to "微信",
        "com.tencent.wework" to "企业微信",
        "com.alibaba.android.rimet" to "钉钉",
        "com.ss.android.lark" to "飞书",
        "com.tencent.tim" to "TIM",
        "com.immomo.momo" to "陌陌",
        // 购物 / 支付
        "com.taobao.taobao" to "淘宝",
        "com.tmall.wireless" to "天猫",
        "com.jingdong.app.mall" to "京东",
        "com.xunmeng.pinduoduo" to "拼多多",
        "com.taobao.idlefish" to "闲鱼",
        "com.eg.android.AlipayGphone" to "支付宝",
        "com.unionpay" to "云闪付",
        "com.sankuai.meituan" to "美团",
        "me.ele" to "饿了么",
        "com.dianping.v1" to "大众点评",
        "com.vipshop.iphone" to "唯品会",
        "com.shizhuang.duapp" to "得物",
        "com.hpbr.bosszhipin" to "BOSS直聘",
        // 出行 / 旅行
        "com.autonavi.minimap" to "高德地图",
        "com.baidu.BaiduMap" to "百度地图",
        "com.sdu.didi.psnger" to "滴滴出行",
        "com.MobileTicket" to "铁路12306",
        "ctrip.android.view" to "携程",
        "com.Qunar" to "去哪儿",
        // 银行 / 金融
        "cmb.pb" to "招商银行",
        "com.chinamworld.main" to "建设银行",
        "com.icbc" to "工商银行",
        "com.android.bankabc" to "农业银行",
        "com.chinamworld.bocmbci" to "中国银行",
        // 工具 / 系统
        "com.android.settings" to "设置",
        "com.android.systemui" to "系统界面",
        "com.android.launcher" to "桌面",
        "com.android.launcher3" to "桌面",
        "com.bbk.launcher2" to "桌面",
        "com.vivo.launcher" to "桌面",
        "com.android.camera" to "相机",
        "com.android.gallery3d" to "相册",
        "com.android.documentsui" to "文件",
        "com.android.calculator2" to "计算器",
        "com.android.calendar" to "日历",
        "com.android.deskclock" to "时钟",
        "com.android.mms" to "短信",
        "com.android.dialer" to "电话",
        "com.android.contacts" to "联系人",
        "com.android.browser" to "浏览器",
        "com.android.vending" to "应用商店",
        "com.vivo.appstore" to "应用商店",
        "com.tencent.mtt" to "QQ浏览器",
        "com.UCMobile" to "UC浏览器",
        "com.baidu.searchbox" to "百度",
        "com.quark.browser" to "夸克",
        "com.tencent.android.qqdownloader" to "应用宝",
        "com.tencent.qqpimsecure" to "腾讯手机管家",
        "com.iflytek.inputmethod" to "讯飞输入法",
        "com.sohu.inputmethod.sogou" to "搜狗输入法",
        "com.baidu.input" to "百度输入法",
        // 游戏（MonitorService 会跳过，但列表里可能出现）
        "com.tencent.tmgp.sgame" to "王者荣耀",
        "com.tencent.tmgp.pubgmhd" to "和平精英",
        "com.netease.yys" to "阴阳师",
        "com.miHoYo.Yuanshen" to "原神",
        // 自己
        "com.trace.app" to "Trace",
    )

    /**
     * 取应用显示名。任何一步失败都不会抛，最差返回包名末段。
     * @param ctx 可空：后台线程拿不到 Context 时会退回兜底表。
     */
    fun of(ctx: Context?, pkg: String?): String {
        val key = pkg?.trim().orEmpty()
        if (key.isEmpty()) return "未知"

        cache[key]?.let { return it }

        var name = ""
        if (ctx != null) {
            name = try {
                val pm = ctx.packageManager
                val ai = pm.getApplicationInfo(key, 0)
                pm.getApplicationLabel(ai).toString().trim()
            } catch (t: Throwable) {
                ""
            }
        }
        if (name.isEmpty()) name = fallback(key)

        cache[key] = name
        return name
    }

    /** 不依赖 Context 的版本（服务/日志里用）。 */
    fun of(pkg: String?): String {
        val key = pkg?.trim().orEmpty()
        if (key.isEmpty()) return "未知"
        cache[key]?.let { return it }
        return fallback(key)
    }

    private fun fallback(pkg: String): String {
        FALLBACK[pkg]?.let { return it }
        FALLBACK.entries.firstOrNull { it.key.equals(pkg, ignoreCase = true) }?.let { return it.value }
        val tail = pkg.substringAfterLast('.')
        return if (tail.isBlank()) pkg else tail
    }

    /** 只清缓存（应用名可能随系统语言/应用更新变化，留个口子）。 */
    fun invalidate() = cache.clear()
}

package com.trace.app.ai

import android.content.Context
import com.trace.app.behavior.UsageStatsHelper
import com.trace.app.db.TraceDbHelper
import com.trace.app.model.Record
import com.trace.app.util.AppNames
import com.trace.app.vlm.VlmEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * 每日 AI 总结（端侧优先）。
 *
 * 与聊天页的区别：聊天是「用户问 → 模型答」，这里是**主动**把一天的散点数据
 * 压成一段人话，所以输入不是原始事件流，而是**已经聚合好的关键数字**
 * （总时长 / 时段分布 / Top 应用 / 内容行为 / 消费 / 在干什么）。
 *
 * 模型是 2.4 GB 的端侧小模型，提示词必须"结构化 + 给例子"才写得像样：
 * 规则在前、数据在中、一句"现在开始写"在后（利用近因），并给一段示例示范语气与长度。
 */
object DailySummary {

    /**
     * 总结专用系统指令。
     *
     * 踩过的坑：**示例里不要放具体数字**。上一版示例写了「晚上 10 点那笔 128 元的餐饮」，
     * 2.4 GB 的小模型直接把 128 元照抄进总结里，而当天根本没有这笔账。
     * 现在只给骨架、不给数字，并明确禁止编造。
     */
    const val INSTRUCTION: String = """你是一位贴身的生活观察助手，为手机主人写「今日总结」。
输入是从这台手机本机采集的今日数据，全部在本机处理，不要提隐私。

最重要的一条：数据开头有【今日看点】，那是已经从数据里挑好的、今天最值得写的事实。
你的任务是把它们讲成人话，不是重新筛选。

写作规则：
- 只输出总结正文：一到两段连续文字。不要标题、不要编号、不要 Markdown 符号、不要复述规则。
- 全文写成 5-7 句话：前 5 句一句对应【今日看点】的一条（顺序不要变、一条都不能漏），最后一句给建议。
- 数字必须写成阿拉伯数字：写「18 分钟」「22 个视频」「2 小时 34 分」。
  严禁写成 eighteen、nineteen 这类英文单词，也不要写成「二十两」这种别扭的汉字数字。
- 只能用给出的数据。禁止编造金额、次数、应用名、内容标题；数据里没有的，宁可不写。
- 第 1 句直接讲今天的屏幕时间（看点第 1 条），不要说「今天看…」这类口头语。
- 最后一句建议要提到今天的一个具体数字或应用名，不要说空话。
- 语气像懂主人的朋友，可以轻微调侃，但不要说教；禁止「注意休息」「合理安排时间」这类放之四海皆准的空话。
- 禁止写「今天的总结就是…」「本文…」「总之」「以上就是」这类元话和总结句，直接讲事实。
- 全文 150-250 字，宁可多写一句细节，也不要短于 150 字。
- 如果数据确实很少，就如实说今天数据太少，不要编、不要凑。

骨架（照着结构写，不要照抄词）：
今天……（用看点第 1 条）。……（看点里的现象，带数字）。……（看点里的现象，带数字）。明天……（一条针对今天数据的建议）。

"""

    /**
     * 端侧「写解读」阶段的系统指令（v3：四字段 + 允许引用数字）。
     *
     * 事实段由代码写好（见 [factsText]），这里只要「事实之外的意思」：
     * 小模型自己从一堆数字里挑重点会写成流水账，所以「挑事实」在代码里做完，
     * 模型负责**把事实读成人话**——对照、点名、指模式。
     *
     * v2 曾禁止它写任何数字，结果正好把「不是看是打：王者 1 小时 41 分占三分之一」
     * 这类最像样的句子也堵死了；改成「数字只能从输入里原样引用」，
     * 再由 [numbersOk] 在代码里逐个数校验（编一个不存在的数字就丢弃该字段）。
     */
    const val POLISH_INSTRUCTION: String = """你是一位贴身的生活观察助手。用户会给你今天的原始事实（屏幕时间、各应用时长与打开次数、今天看到的内容标题、消费记录）。

输出格式：只输出一个 JSON 对象，四个键固定为 yihua、liangdian、guanzhu、jianyi：
{"yihua":"一句话","liangdian":["亮点1","亮点2"],"guanzhu":["值得留意的事"],"jianyi":"明天做的一件事"}

硬性要求（逐条满足）：
1. liangdian 写 2-4 条，每条不超过 60 字，且必须同时做到：
   - 至少一条把两件**同类**的东西摆在一起对比，用「不是 A，是 B：具体数据」的句式；
     A 和 B 必须从事实里【可以对比的同类项】那几条里挑（应用对应用、时段对时段、主题对主题），
     不许拿一个应用去比一类内容，也不许提没在里面出现过的应用名；那几条都不合适就别硬写这一条；
   - 至少一条用「」引出事实里【今天的主题】给出的一个主题词，说清今天看的东西集中在什么主题上；
   - 每条都要带上事实里给出的数字。
2. guanzhu 写 1-3 条，每条不超过 60 字，指出矛盾、盲区或反常：
   - 只有事实里【打开方式】**明确标了「（点开就关）」**的应用，才能说它是「扫」不是「看」；
     没标的应用（平均每次几十秒以上）不许说「扫」，只能照实说「平均每次 N 秒」；
   - 事实里【消费】写「今天没有记账」时，必须有一条讲这件事（带上昨天花了多少或刷到几条花钱内容）。
3. jianyi 写一条明天能做的，不超过 40 字，必须点名事实里的一个应用或一条内容；
   只能往「少看、换着看、看点别的、拿去做别的事」方向建议，绝对不许建议他多刷、多看、继续看。
4. yihua 一句话概括今天，不超过 40 字，不要复述数字；能用「不是 A，是 B」把今天最占时间的那件事说清最好。

数字与事实：
- 数字只能照抄事实里已经给出的，不许换算、不许四舍五入、不许新造。
- 尤其**不许做加减法**：像「多出 13 次」「比上午少 125 分钟」这种由你自己算出来的数字一律不写，
  只写事实里原样出现过的数字（实测两版模型都会忍不住算，算出来的数会被校验闸丢掉整条）。
- 不许编造事实里没有的应用、内容、金额、事情。
- 不要整句复述事实，要给出事实之外的意思（为什么值得注意、两边对比说明了什么）。
- 不许写「注意休息」「合理安排时间」「保持良好习惯」这类空话，也不要反过来劝他多刷。

格式与语气：
- 只输出 JSON 本身：不要解释、不要注释、不要代码块，不要用「好：」「观察：」这类标签开头。
- yihua 和 liangdian 不要出现重复的句子，同一个意思只说一次。
- 语气像懂主人的朋友：可以说实话、可以轻微调侃，但不要说教。"""

    /** 写解读阶段的上限（四字段合计 250 字左右 ≈ 200 token，留余量）。 */
    private const val POLISH_TOKENS = 320

    data class Result(
        val ok: Boolean,
        val day: String,
        val text: String,
        val engine: String,
        val model: String,
        val stats: String,
        val error: String = "",
        /** 字段化总结（JSON 文本），留给卡片分区渲染与周报聚合；模型没参与时为空。 */
        val json: String = "",
    )

    /** 喂给模型的完整数据块 + 挑好的【今日看点】+ 今天真实出现过的应用名（判「是不是编的」用）。 */
    data class Stats(
        val text: String,
        val highlights: List<String>,
        val appNames: List<String> = emptyList(),
    )

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    fun dayKey(ts: Long = System.currentTimeMillis()): String = dayFmt.format(Date(ts))

    /** 某天的 [起, 止) 毫秒区间。 */
    fun dayRange(day: String): Pair<Long, Long> {
        val d = LocalDate.parse(day)
        val zone = ZoneId.systemDefault()
        val start = d.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    fun dur(ms: Long): String {
        val min = ms / 60000
        return when {
            min >= 60 -> "${min / 60} 小时 ${min % 60} 分"
            min >= 1 -> "$min 分钟"
            else -> "不到 1 分钟"
        }
    }

    /**
     * 把一天的库表聚合成给模型看的「关键数字」。
     * 同时也存进 daily_digest.stats —— 以后嫌总结不对，可以直接看喂了什么。
     */
    fun buildStats(ctx: Context, db: TraceDbHelper, day: String): Stats {
        val (start, end) = dayRange(day)
        val sb = StringBuilder()
        val usage = UsageStatsHelper(ctx)
        // 屏幕时间 / 时段分布 / 应用，全部取自系统 UsageStats 的同一份事件流，
        // 避免「总计不到 1 分钟、时段却 1 小时 35 分」这种自相矛盾。
        val sys = usage.dayUsage(start, end, topN = 8)

        sb.appendLine("===== $day 今日数据（本机采集）=====")
        sb.appendLine("【屏幕时间】总计 ${dur(sys.totalMs)}")

        val slotNames = listOf("凌晨 0-6 点", "上午 6-12 点", "下午 12-18 点", "晚上 18-24 点")
        sb.appendLine(
            "【时段分布】" + slotNames.mapIndexed { i, n -> "$n ${dur(sys.slots[i])}" }.joinToString(" · ")
        )

        // 应用 Top 排掉 Trace 自己/桌面/系统 UI：那些对「今天过得怎么样」没有信息量。
        val topApps = sys.apps.filter { it.pkg !in SKIP_APPS }
        if (topApps.isEmpty()) {
            sb.appendLine("【应用】今天没有采集到应用使用数据")
        } else {
            sb.appendLine(
                "【应用 Top】" + topApps.joinToString(" · ") { a ->
                    var s = a.name + " " + dur(a.ms)
                    if (a.opens > 0) s += "（打开 ${a.opens} 次）"
                    // 占比先在代码里算好：让模型做除法，它会算错或编一个数。
                    if (sys.totalMs > 0) {
                        val share = (a.ms * 100 / sys.totalMs).toInt()
                        if (share >= 10) s += "占屏幕时间 $share%"
                    }
                    // 打开很多次、每次都很短 = 在「扫」而不是在「读」；数字算好，判断留给模型。
                    if (a.opens >= 20 && a.ms > 0) s += "，平均每次 ${a.ms / a.opens / 1000} 秒"
                    s
                }
            )
        }

        // ---- 打开方式（扫 vs 读）----
        // 之前只把「平均每次 N 秒」附在应用行末尾，模型会把「扫」安到别的应用上
        // （实测安到「凌晨 21 分钟」那次）。抽成单独一块、把结论写在数字旁边，
        // 归因就落在正确的应用上。
        val scrollers = topApps.filter { it.opens >= 20 && it.ms > 0 }
        if (scrollers.isNotEmpty()) {
            sb.appendLine(
                "【打开方式】（只有标了「（点开就关）」的才算在扫，没标的按平均每次多少秒照实说）" +
                    scrollers.joinToString(" · ") { a ->
                        val per = a.ms / a.opens / 1000
                        a.name + " 打开 " + a.opens + " 次 / 共 " + dur(a.ms) +
                            " = 平均每次 " + per + " 秒" + (if (per < 10) "（点开就关）" else "")
                    }
            )
        }

        // ---- 在干什么（去重计数）----
        // 过滤掉桌面/自己：那些是系统噪声，不是「主人在干什么」。
        val acts = db.activityLogInRange(start, end, limit = 200)
            .filter { it.pkg != "com.trace.app" && it.pkg != "com.bbk.launcher2" }
        if (acts.isNotEmpty()) {
            val counter = LinkedHashMap<String, Int>()
            acts.forEach { a ->
                val key = AppNames.of(ctx, a.pkg) + "·" + a.what
                counter[key] = (counter[key] ?: 0) + 1
            }
            sb.appendLine(
                "【在干什么】" + counter.entries.sortedByDescending { it.value }.take(8)
                    .joinToString(" · ") { "${it.key}×${it.value}" }
            )
        }

        // ---- 内容行为 ----
        val content = db.contentEventCounts(start, end)
        val kind = mapOf(
            "VIDEO_CHANGED" to "换视频", "LIKE" to "点赞", "UNLIKE" to "取消点赞",
            "SAVE" to "收藏", "UNSAVE" to "取消收藏"
        )
        val contentEvents = db.contentEventsInRange(start, end, limit = 200)
        // 样本只取内容事件里的真实标题（抖音/小红书这类），
        // 别再拿 activity_log 的界面文本——那是别的 App 自己的文案，纯噪声。
        // 条数从 3 提到 8：只给 2-3 条时模型写不出「今天在看什么」这类归纳，
        // 而「内容的主题」正是总结里最有信息量的部分。
        // 提到 if 外面：消费那段也要用它（数一下今天刷到几条花钱/赚钱的内容）。
        val samples = contentEvents
            .map { (if (it.author.isBlank()) "" else it.author + "：") + it.title }
            .filter { it.length > 6 }
            .map { it.take(60) }
            .distinct()
            .take(8)
        val themes = themesOf(samples)
        if (content.isNotEmpty()) {
            sb.appendLine(
                "【内容行为】" + content.entries.sortedByDescending { it.value }
                    .joinToString(" · ") { "${kind[it.key] ?: it.key} ${it.value} 次" }
            )
            if (samples.isNotEmpty()) {
                sb.appendLine("【今天看到的内容】（标题原文，判断他在看什么用）")
                samples.forEachIndexed { i, t -> sb.appendLine("${i + 1}. $t") }
                if (themes.isNotEmpty()) {
                    sb.appendLine("【今天的主题】（代码已从上面标题归纳好，正文必须引用其中一个）")
                    sb.appendLine(themes.joinToString(" / ") { "${it.first}（${it.second} 条）" })
                }
            }

            val liked = contentEvents
                .filter { it.event == "LIKE" || it.event == "SAVE" }
                .map { (if (it.author.isBlank()) "" else it.author + "：") + it.title }
                .filter { it.length > 4 }
                .map { it.take(40) }
                .distinct()
                .take(4)
            if (liked.isNotEmpty()) sb.appendLine("【点过赞/收藏的内容】" + liked.joinToString(" / "))
        }

        // ---- 消费 ----
        // 昨日消费要先算：今天没记账时，「昨天花了多少」+「今天刷到几条花钱内容」
        // 才是模型能写的那句对照（「刷到最容易掏钱的内容，你一笔没下」）。
        // 只写「今天没有记账」，模型就只会把这个事实复述一遍。
        val (pStart, pEnd) = dayRange(LocalDate.parse(day).minusDays(1).toString())
        val prevTotal = usage.dayUsage(pStart, pEnd, topN = 1).totalMs
        val prevSpend = db.recordsInRange(pStart, pEnd).sumOf { it.amount }
        val records = db.recordsInRange(start, end)
        if (records.isEmpty()) {
            val money = samples.count { t -> MONEY_KEYS.any { t.contains(it) } }
            sb.appendLine(
                "【消费】今天没有记账" +
                    (if (prevSpend > 0) "（昨天 ¥" + "%.2f".format(prevSpend) + "）" else "") +
                    (if (money > 0) "；但今天刷到的内容里有 $money 条是关于花钱/赚钱/省钱的" else "")
            )
        } else {
            val sum = records.sumOf { it.amount }
            val max = records.maxByOrNull { it.amount }
            val night = records.filter { it.isNight }
            val byCat = records.groupBy { it.category }.mapValues { m -> m.value.sumOf { it.amount } }
                .entries.sortedByDescending { it.value }
            sb.appendLine(
                "【消费】共 ${records.size} 笔 ¥%.2f".format(sum) +
                    "；分类：" + byCat.take(4).joinToString(" · ") { "${it.key} ¥%.2f".format(it.value) }
            )
            if (max != null) {
                sb.appendLine(
                    "【最大一笔】¥%.2f %s %s".format(
                        max.amount, max.category, (max.merchant ?: max.note ?: "").take(20)
                    )
                )
            }
            if (night.isNotEmpty()) {
                sb.appendLine(
                    "【深夜消费】${night.size} 笔 ¥%.2f（23:00-03:00 记的）".format(night.sumOf { it.amount })
                )
            }
        }

        // ---- 屏幕理解 ----
        val frames = db.frameCountInRange(start, end)
        if (frames > 0) sb.appendLine("【屏幕理解】今天存了 $frames 张关键帧")

        // ---- 昨日对比 ----
        if (prevTotal > 0 || prevSpend > 0) {
            val cmp = if (prevTotal > 0) {
                val pct = (sys.totalMs - prevTotal) * 100.0 / prevTotal
                "屏幕时间昨日 %s（今天 %+.0f%%）".format(dur(prevTotal), pct)
            } else {
                "昨日无屏幕数据"
            }
            // 注意：cmp 里可能带 %（上面 %+.0f%% 格式化后的产物），
            // 这里必须拼字符串而不是再 .format()，否则会 UnknownFormatConversionException。
            sb.appendLine("【昨日对比】" + cmp + " · 消费昨日 ¥" + "%.2f".format(prevSpend))
        }

        // 同类对比也由代码配好：模型被要求写「不是 A，是 B」时会把两类东西乱配
        // （实测写出过「不是微信，是剧集：17 次王者荣耀」）。给它现成的同类对标，
        // 它只负责挑一条、把话说顺。
        val pairs = comparePairs(topApps, slotNames, sys.slots, themes, prevSpend, records.isEmpty())
        if (pairs.isNotEmpty()) {
            sb.appendLine("【可以对比的同类项】（写「不是 A，是 B」时只能从这几条里挑，不要自己乱配）")
            pairs.forEach { sb.appendLine("· $it") }
        }

        // 把「最值得写的几条」在代码里挑好、放在最前面。
        // 端侧只有 2.4 GB 的小模型：让它自己从一堆数字里挑重点，它会写成流水账或抓错重点；
        // 把「挑」交给代码、只让它把挑好的事实写成人话，质量完全不同。
        val highlights = highlights(sys, content, records, prevTotal)
        if (highlights.isNotEmpty()) {
            sb.insert(
                0,
                "【今日看点】（已按重要性排好，正文里每条都要提到）\n" +
                    highlights.mapIndexed { i, h -> "${i + 1}. $h" }.joinToString("\n") + "\n\n"
            )
        }

        return Stats(sb.toString(), highlights, sys.apps.map { it.name })
    }

    /** Trace 自己、桌面、系统 UI 不算「主人在用手机」。 */
    private val SKIP_APPS = setOf("com.trace.app", "com.bbk.launcher2", "com.android.systemui")

    /** 从数据里挑出今天最值得写的 3-5 条事实（带数字）。 */
    private fun highlights(
        sys: UsageStatsHelper.DayUsage,
        content: Map<String, Int>,
        records: List<Record>,
        prevTotal: Long,
    ): List<String> {
        val out = ArrayList<String>()

        if (sys.totalMs > 0) {
            var s = "屏幕时间 ${dur(sys.totalMs)}"
            if (prevTotal > 0) {
                val pct = (sys.totalMs - prevTotal) * 100.0 / prevTotal
                val word = if (pct >= 0) "多了" else "少了"
                s += "，昨天 ${dur(prevTotal)}（$word ${"%.0f".format(kotlin.math.abs(pct))}%）"
            }
            out.add(s)
        }

        sys.apps.firstOrNull { it.pkg !in SKIP_APPS && it.ms >= 120_000L }?.let {
            // 中文里不要在「抖音」和「用了」之间留空格，事实段是直接念出来的。
            out.add(it.name + "用了 " + dur(it.ms) + if (it.opens > 0) "，打开 ${it.opens} 次" else "")
        }

        val changed = content["VIDEO_CHANGED"] ?: 0
        if (changed >= 5) out.add("刷短视频换了 $changed 个视频")

        val social = ArrayList<String>()
        (content["LIKE"] ?: 0).takeIf { it > 0 }?.let { social.add("点赞 $it 条") }
        (content["SAVE"] ?: 0).takeIf { it > 0 }?.let { social.add("收藏 $it 条") }
        (content["UNLIKE"] ?: 0).takeIf { it > 0 }?.let { social.add("取消点赞 $it 次") }
        if (social.isNotEmpty()) out.add(social.joinToString("、"))

        if (sys.slots[0] >= 5 * 60_000L) out.add("凌晨 0-6 点还在用手机 ${dur(sys.slots[0])}")

        if (records.isNotEmpty()) {
            val sum = records.sumOf { it.amount }
            var s = "记了 ${records.size} 笔消费共 ¥" + "%.2f".format(sum)
            records.filter { it.isNight }.takeIf { it.isNotEmpty() }?.let {
                s += "，其中深夜 ${it.size} 笔 ¥" + "%.2f".format(it.sumOf { r -> r.amount })
            }
            records.maxByOrNull { it.amount }?.let {
                s += "，最大一笔 ¥" + "%.2f".format(it.amount) + "（" + it.category + "）"
            }
            out.add(s)
        }

        return out.take(5)
    }

    /** 今天是否已经有总结。 */
    fun hasDigestToday(db: TraceDbHelper, day: String = dayKey()): Boolean = db.latestDigest(day) != null

    /**
     * 生成某天的总结并留档。
     *
     * 引擎跟随聊天页的设置：默认端侧（免费、离线）；用户在聊天设置里切到云端且填了 Key，
     * 这里也会用云端（更快、文案更稳）。
     */
    suspend fun generate(
        ctx: Context,
        db: TraceDbHelper,
        day: String = dayKey(),
        force: Boolean = false,
    ): Result {
        // 统计要跑十几个 SQL + 两次系统 UsageStats 查询，任何一处出问题都不能把 App 带走：
        // 这里兜住异常，转成一条可展示的错误（曾经因为字符串 % 转义崩过一次）。
        return try {
            generateInner(ctx, db, day, force)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result(
                false, day, "", "local", "", "",
                "生成失败：${e.javaClass.simpleName}${e.message?.let { "（$it）" } ?: ""}"
            )
        }
    }

    private suspend fun generateInner(
        ctx: Context,
        db: TraceDbHelper,
        day: String,
        force: Boolean,
    ): Result {
        if (!force) {
            db.latestDigest(day)?.let {
                return Result(true, day, it.text, it.engine, it.model, it.stats, json = it.json)
            }
        }

        val st = buildStats(ctx, db, day)
        val stats = st.text
        val prefs = ctx.getSharedPreferences("glm_config", Context.MODE_PRIVATE)
        val cloud = prefs.getString("engine", "local") == "cloud"
        val key = prefs.getString("api_key", "").orEmpty()
        val model = prefs.getString("model", "glm-4-flash") ?: "glm-4-flash"

        if (cloud && key.isNotBlank()) {
            return try {
                val text = clean(
                    withContext(Dispatchers.IO) {
                        GlmApi.chat(
                            key, model,
                            listOf(GlmApi.Message("user", INSTRUCTION + stats + "\n现在请写今天的总结。"))
                        )
                    }
                )
                db.addDigest(day, "cloud", model, text, stats, "")
                Result(true, day, text, "cloud", model, stats)
            } catch (e: Exception) {
                // 云端失败不留空档：退回端侧，保证「今天总有一份总结」。
                localGenerate(ctx, db, day, stats, st.highlights, st.appNames)
            }
        }
        return localGenerate(ctx, db, day, stats, st.highlights, st.appNames)
    }

    /**
     * 端侧：**事实段由代码写、模型只补一句观察 + 一句建议**，补的话以 JSON 字段交回。
     *
     * 为什么不整段交给模型：小模型的三个毛病改不掉——挑重点会漏、抄数字会错
     * （把 22 写成「二十两」、把 eighteen 写成英文）、建议会说空话。
     * 改成混合式之后：数字 100% 来自本机统计、看点 100% 覆盖，模型只负责它最擅长的语言部分。
     *
     * 模型这一侧还有两道闸（都是实测踩出来的）：
     * ① 抄事实 → [echoesFact] 丢掉；② 编出今天没发生的事（早期版本编过「咖啡馆」）→
     * [grounded] 要求它至少命中一个今天真实出现过的应用名或行为词，否则丢掉。
     * 两道闸都拦掉时就只交付事实段，`model` 如实标成「本机统计」。
     */
    private suspend fun localGenerate(
        ctx: Context,
        db: TraceDbHelper,
        day: String,
        stats: String,
        highlights: List<String>,
        appNames: List<String>,
    ): Result {
        // 「用哪颗脑子写的」从引擎问，别写死：屏幕理解用 VLM，写总结用用户选的文本模型。
        val model = VlmEngine.chatModelName(ctx)
        val facts = factsText(highlights)
        if (facts.isBlank()) {
            // 连看点都挑不出来（今天几乎是空数据），那就没得总结。
            return Result(false, day, "", "local", model, stats, "今天的数据太少，没法总结")
        }

        // 整块数据都喂给它。早期只喂「5 条看点」，结果内容标题、打开次数、消费记录
        // 明明在 stats 里却没进模型眼睛 → 它当然写不出「在看什么」。
        val ask = "今天的全部事实：\n" + stats + "\n\n按格式要求输出那个 JSON。"
        val r = VlmEngine.ask(ctx, ask, maxTokens = POLISH_TOKENS, instruction = POLISH_INSTRUCTION)
        val p = if (r.ok) parsePolish(clean(r.answer)) else Polish.EMPTY

        // 四道闸：别抄事实段、别编数字、别编引号里的内容。
        // 注意：亮点早先还要求「必须锚在真实应用/行为上」，实测把好句子也误杀了
        // ——模型写「今天看的都是剧：…」时没点名应用也没带数字，但它引的主题词
        // 是输入里【今天的主题】给的，本来是对的 → 改成只靠 数字/引号 两道硬闸。
        fun accept(s: String, max: Int, needGround: Boolean): Boolean =
            s.isNotBlank() && s.length <= max && looksClean(s) &&
                !echoesFact(s, facts) && numbersOk(s, stats) && quotedOk(s, stats) &&
                (!needGround || grounded(s, appNames))

        /** 被哪道闸拦下的——不查日志就只能靠猜，这一行是给以后调提示词用的。 */
        fun why(s: String, max: Int, needGround: Boolean): String = when {
            s.isBlank() -> "空"
            s.length > max -> "超长"
            !looksClean(s) -> "像标签/元话"
            echoesFact(s, facts) -> "抄事实段"
            !numbersOk(s, stats) -> "数字对不上"
            !quotedOk(s, stats) -> "引号里的内容不在输入里"
            needGround && !grounded(s, appNames) -> "没锚点"
            else -> "ok"
        }

        val one = p.yihua.takeIf { accept(it, 60, true) } ?: ""
        // 模型偶尔把同一句话原样塞进 yihua 和 liangdian（实测出现过），去重。
        val liang = p.liangdian
            .filter { accept(it, 90, false) && !sameish(it, one) }
            .distinct()
            .take(4)
        val guan = p.guanzhu.filter { accept(it, 90, false) }.distinct().take(3)
        // 「明天别忘了抽时间在抖音看看」——实测 MiniCPM5-2B 真写出过这种劝多刷的建议，
        // 方向和整个产品相反，宁可不要这条建议。
        val advice = p.jianyi.takeIf { accept(it, 60, false) && !pushesMore(it) } ?: ""
        android.util.Log.i(
            "TraceVlm",
            "闸门 liang=" + p.liangdian.joinToString(" | ") { why(it, 90, false) } +
                " guan=" + p.guanzhu.joinToString(" | ") { why(it, 90, false) } +
                " yihua=" + why(p.yihua, 60, true) +
                " jianyi=" + why(p.jianyi, 60, false) + (if (pushesMore(p.jianyi)) "(劝多刷)" else "") +
                " raw=" + p.liangdian.joinToString(";") + " ~~ " + p.guanzhu.joinToString(";") + " ~~ " + p.jianyi
        )
        val modelUsed = one.isNotBlank() || liang.isNotEmpty() || guan.isNotEmpty() || advice.isNotBlank()

        // 事实段永远在最前面：数字全部来自本机统计，模型碰不到；
        // 后面才是模型的解读（它写崩了就只交付事实段，model 如实标「本机统计」）。
        val text = if (!modelUsed) facts else buildString {
            append(facts)
            if (one.isNotBlank()) append("\n").append(one)
            liang.forEach { append("\n· ").append(it) }
            guan.forEach { append("\n留意：").append(it) }
            if (advice.isNotBlank()) append("\n明天：").append(advice)
        }
        val json = digestJson(one, liang, guan, advice)
        val modelName = if (modelUsed) model else "本机统计"
        db.addDigest(day, "local", modelName, text, stats, json)
        return Result(true, day, text, "local", modelName, stats, json = json)
    }

    /** 模型吐出来的四个字段。 */
    data class Polish(
        val yihua: String,
        val liangdian: List<String>,
        val guanzhu: List<String>,
        val jianyi: String,
    ) {
        companion object {
            val EMPTY = Polish("", emptyList(), emptyList(), "")
        }
    }

    /** 存库用的字段化总结：{ "yihua":…, "liangdian":[…], "guanzhu":[…], "jianyi":… }。 */
    private fun digestJson(one: String, liang: List<String>, guan: List<String>, advice: String): String =
        "{\"yihua\":" + JSONObject.quote(one) +
            ",\"liangdian\":" + jsonArrayOf(liang) +
            ",\"guanzhu\":" + jsonArrayOf(guan) +
            ",\"jianyi\":" + JSONObject.quote(advice) + "}"

    private fun jsonArrayOf(xs: List<String>): String =
        "[" + xs.joinToString(",") { JSONObject.quote(it) } + "]"

    private fun jsonStrings(o: JSONObject, key: String): List<String> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optString(i).trim().takeIf { it.isNotBlank() } }
    }

    /**
     * 从模型输出里抠出四个字段。
     *
     * 小模型的坏输出都在这里兜住：被 ```json 围栏包住、前后带解释、抄了模板里的标签
     * （实测 MiniCPM5-2B 会把 few-shot 的「好：」当句子开头照抄，所以提示词里已经不放假例子了）。
     */
    private fun parsePolish(raw: String): Polish {
        if (raw.isBlank()) return Polish.EMPTY
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        }
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        if (a >= 0 && b > a) s = s.substring(a, b + 1)
        return try {
            val o = JSONObject(s)
            Polish(
                o.optString("yihua").trim(),
                jsonStrings(o, "liangdian"),
                jsonStrings(o, "guanzhu"),
                o.optString("jianyi").trim(),
            )
        } catch (t: Throwable) {
            Polish.EMPTY
        }
    }

    /** 两句话是不是一个意思：去标点后完全相等、互相包含，或前 8 字一样。 */
    private fun sameish(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        val x = a.replace(Regex("[\\s，。；：、！？·（）()]"), "")
        val y = b.replace(Regex("[\\s，。；：、！？·（）()]"), "")
        if (x == y || x.contains(y) || y.contains(x)) return true
        return x.length >= 8 && y.length >= 8 && x.take(8) == y.take(8)
    }

    /** 拒绝标签开头（「好：」「注：」）和括号注释——都是模型自说自话的痕迹。 */
    private val LABEL_RE = Regex("^\\s*(好|坏|观察|建议|一句话|总结|注|备注)\\s*[:：]")

    /**
     * 劝人多看/多刷的建议一律不要。
     * 实测 MiniCPM5-2B 写过「明天别忘了抽时间在抖音看看」——产品是帮他看清自己，
     * 不是帮他刷得更久。只认「再刷一点」的句式，不误伤「少看/换着看」。
     */
    private val PUSH_MORE_RE =
        Regex("(多|继续|接着|再|抽时间|花点时间|挤出时间|腾出时间)[^，。；！？]{0,8}(刷|看|逛|玩|听)")

    /** 「别再刷短视频」是在劝他停——否定词开头的句式不能当成劝多刷。 */
    private val NEG_BEFORE = listOf("别", "不要", "少", "不再", "莫", "勿", "停止")

    private fun pushesMore(s: String): Boolean {
        for (m in PUSH_MORE_RE.findAll(s)) {
            val head = s.substring(maxOf(0, m.range.first - 3), m.range.first)
            if (NEG_BEFORE.any { head.contains(it) }) continue
            return true
        }
        return false
    }

    private fun looksClean(s: String): Boolean =
        !LABEL_RE.containsMatchIn(s) &&
            !s.contains("（注") && !s.contains("(注") &&
            !s.contains("无法生成") && !s.contains("违反") && !s.contains("指令")

    /**
     * 判断这句话是不是在讲今天真发生过的事：至少要命中一个真实应用名、一个行为词，或一个真实数字。
     * 命中不了就认为它在编（实测编出过「早起第一杯水」「把咖啡换成薄荷茶」）。
     */
    private fun grounded(s: String, appNames: List<String>): Boolean =
        (appNames + BEHAVIOR_WORDS).any { it.length >= 2 && s.contains(it) } ||
            Regex("\\d").containsMatchIn(s)

    /**
     * 输出里的每个阿拉伯数字都必须在输入里原样出现过——数字只能引用，不能自造。
     * 早期的「不许写任何数字」太狠，把「占三分之一」这类最像样的句子也堵死了；
     * 改成放行 + 逐个数校验：编一个今天不存在的数（比如没有的 128 元）就丢掉该字段。
     */
    private fun numbersOk(s: String, input: String): Boolean {
        // 必须按「数字令牌」逐个比对，不能用 substring：
        // 实测放过过假的「减少到 20 次以下」——因为输入里的日期 2026 含子串 "20"。
        val allowed = Regex("\\d+(?:\\.\\d+)?").findAll(input).map { it.value }.toSet()
        return Regex("\\d+(?:\\.\\d+)?").findAll(s).all { it.value in allowed }
    }

    /**
     * 同类对标：把「可以摆在一起说的两件事」配好。
     * 只配**同类**（应用对应用、时段对时段、主题对主题），因为模型自己配的时候
     * 会拿一个应用去比一类内容（实测「不是微信，是剧集：17 次王者荣耀」）。
     */
    private fun comparePairs(
        apps: List<UsageStatsHelper.DayUsage.AppUsage>,
        slotNames: List<String>,
        slots: LongArray,
        themes: List<Pair<String, Int>>,
        prevSpend: Double,
        noRecordToday: Boolean,
    ): List<String> {
        val out = ArrayList<String>()
        if (apps.size >= 2) {
            out.add(
                "应用对应用：" + apps[0].name + " " + dur(apps[0].ms) + "（打开 ${apps[0].opens} 次） vs " +
                    apps[1].name + " " + dur(apps[1].ms) + "（打开 ${apps[1].opens} 次）"
            )
        }
        // 时间最长 vs 打开最勤：这条最像「找」和「读」的对照。
        val busiest = apps.filter { it.opens > 0 }.maxByOrNull { it.opens }
        if (apps.isNotEmpty() && busiest != null && busiest.pkg != apps[0].pkg && busiest.opens >= 10) {
            out.add(
                "时长对次数：时间最长的是 " + apps[0].name + "（" + dur(apps[0].ms) + "／只开了 ${apps[0].opens} 次），" +
                    "打开最勤的是 " + busiest.name + "（${busiest.opens} 次／共 " + dur(busiest.ms) + "）"
            )
        }
        val alive = slots.withIndex().filter { it.value > 0 }
        if (alive.size >= 2) {
            val hi = alive.maxByOrNull { it.value }!!
            val lo = alive.minByOrNull { it.value }!!
            if (hi.index != lo.index) {
                out.add("时段对时段：" + slotNames[hi.index] + " " + dur(hi.value) + " vs " + slotNames[lo.index] + " " + dur(lo.value))
            }
        }
        if (themes.size >= 2) {
            out.add("主题对主题：" + themes[0].first + " " + themes[0].second + " 条 vs " + themes[1].first + " " + themes[1].second + " 条")
        }
        if (noRecordToday && prevSpend > 0) {
            out.add("消费对消费：昨天 ¥" + "%.2f".format(prevSpend) + " vs 今天 ¥0.00（一笔没记）")
        }
        return out
    }

    /**
     * 从看过的内容标题里归纳主题词。
     * 小模型不会自己从 8 条标题里归纳——实测两轮提示词（连「必须点名今天看到的内容」都写死了）
     * 都没引用过任何标题，所以把「归纳」这段交给规则：先按关键词把标题归到几个主题，
     * 再把「钱（3 条）」这样的结论喂回去让它引用。归类与计数由代码保证，模型只负责说人话。
     */
    private fun themesOf(titles: List<String>): List<Pair<String, Int>> {
        if (titles.isEmpty()) return emptyList()
        val hits = LinkedHashMap<String, Int>()
        for (t in titles) {
            for ((theme, keys) in THEME_KEYS) {
                if (keys.any { t.contains(it) }) hits[theme] = (hits[theme] ?: 0) + 1
            }
        }
        return hits.entries.sortedByDescending { it.value }.take(3).map { it.key to it.value }
    }

    /** 「钱」这一类的关键词：主题归纳用它，数「今天刷到几条花钱内容」也用它。 */
    private val MONEY_KEYS = listOf(
        "元", "收入", "理财", "基金", "股票", "省钱", "装机", "消费", "价格",
        "优惠", "618", "二手", "赚钱", "副业", "预算",
    )

    private val THEME_KEYS: List<Pair<String, List<String>>> = listOf(
        "钱" to MONEY_KEYS,
        "本事" to listOf(
            "AI", "ai", "模型", "教程", "学习", "知识", "原理", "科普", "代码",
            "编程", "概念", "课程", "技能", "方法",
        ),
        "吃" to listOf("美食", "探店", "吃", "餐厅", "菜", "牛河", "做饭", "食谱", "外卖", "零食"),
        "剧" to listOf("短剧", "电视剧", "电影", "综艺", "直播", "解说", "集"),
        "玩" to listOf("游戏", "王者", "攻略", "上分", "评测", "开箱"),
    )

    /** 引号里点名的内容/应用必须在输入里出现过，防止编出没看过的视频。 */
    private val QUOTED_RE = Regex("[「『“\"]([^」』”\"]{2,24})[」』”\"]")

    private fun quotedOk(s: String, input: String): Boolean =
        QUOTED_RE.findAll(s).all { input.contains(it.groupValues[1]) }

    private val BEHAVIOR_WORDS = listOf(
        "屏幕时间", "短视频", "视频", "点赞", "收藏", "取消点赞", "凌晨", "深夜", "上午", "下午",
        "晚上", "桌面", "相机", "游戏", "聊天", "购物", "外卖", "音乐", "地图", "阅读", "浏览器",
    )

    /** 把挑好的看点连成一段事实（数字全部来自本机统计，模型碰不到）。 */
    private fun factsText(highlights: List<String>): String {
        if (highlights.isEmpty()) return ""
        return "今天" + highlights.joinToString("；") + "。"
    }

    private fun echoesFact(extra: String, facts: String): Boolean {
        if (facts.length < 24) return false
        val probes = listOf(facts.take(12), facts.drop(facts.length / 2).take(12))
        return probes.any { it.isNotBlank() && extra.contains(it) }
    }

    /**
     * 端侧小模型有个顽固毛病：把数字写成英文单词（「用了 eighteen 分钟」）。
     * 提示词里已经禁止，这里再做一道确定性兜底。
     */
    private val EN_NUMBERS = mapOf(
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4",
        "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
        "ten" to "10", "eleven" to "11", "twelve" to "12", "thirteen" to "13",
        "fourteen" to "14", "fifteen" to "15", "sixteen" to "16", "seventeen" to "17",
        "eighteen" to "18", "nineteen" to "19", "twenty" to "20", "thirty" to "30",
        "forty" to "40", "fifty" to "50", "sixty" to "60",
    )

    private fun digits(raw: String): String {
        var s = raw
        for ((word, num) in EN_NUMBERS) s = s.replace(Regex("(?i)\\b$word\\b"), num)
        return s
    }

    /** 去掉小模型爱冒出来的 Markdown 残留与多余空行。 */
    private fun clean(raw: String): String = digits(
        raw
            .replace(Regex("(?m)^\\s*[#>*•\\-]+\\s*"), "")
            .replace("**", "")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\n{2,}"), "\n")
            .trim()
    )
}

package com.trace.app.ai

import android.content.Context
import com.trace.app.behavior.UsageStatsHelper
import com.trace.app.db.TraceDbHelper
import com.trace.app.model.Record
import com.trace.app.util.AppNames
import com.trace.app.vlm.VlmEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
     * 端侧「补两句」阶段的系统指令。
     *
     * 事实段已经由代码写好（见 [factsText]），模型只做加法：
     * 很多小模型一旦拿到完整数据块就会忍不住重写、改数字、编金额，
     * 所以这里明确禁止它碰数字、只允许它接在事实后面说人话。
     */
    const val POLISH_INSTRUCTION: String = """你是一位贴身的生活观察助手。下面给你的是今天的事实，已经写好了。
你要做的只有一件事：接着这些事实，补一句观察和一句建议。

规则：
- 只输出你补的两句话，不要重复事实里的任何句子，不要改写它们，不要复述数字。
- 不要写任何数字、金额、次数、时长（事实里已经有了）。
- 不要标题、不要编号、不要 Markdown、不要写「总结」二字。
- 两句合计不超过 80 字。
- 第一句要说出今天最反常或最突出的一点（看得多还是少、留没留下东西、什么时段不对）。
- 第二句要具体到「明天把哪件事换成哪件事」，落在今天真实出现过的应用、内容或时段上。
- 严禁「注意休息」「合理安排时间」「利用碎片时间」「保持良好习惯」「调整心态」这类放之四海皆准的空话，也不要反过来劝他多刷。
- 语气像懂主人的朋友：可以说实话、可以轻微调侃，但不要说教。

参考写法（不要照抄）：
- 好：刷得比平时快，点赞却只有一条，看的多、留下的少。
- 好：明天试试把凌晨那段时间留给正在追的东西，别让它继续被短视频吃掉。
- 坏：注意休息，合理安排时间。
- 坏：利用碎片时间检查社交动态。"""

    /** 补两句阶段的上限（80 字 ≈ 120-160 token，留点余量）。 */
    private const val POLISH_TOKENS = 200

    data class Result(
        val ok: Boolean,
        val day: String,
        val text: String,
        val engine: String,
        val model: String,
        val stats: String,
        val error: String = "",
    )

    /** 喂给模型的完整数据块 + 其中挑好的【今日看点】。 */
    data class Stats(val text: String, val highlights: List<String>)

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
                    a.name + " " + dur(a.ms) + if (a.opens > 0) "（打开 ${a.opens} 次）" else ""
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
        if (content.isNotEmpty()) {
            sb.appendLine(
                "【内容行为】" + content.entries.sortedByDescending { it.value }
                    .joinToString(" · ") { "${kind[it.key] ?: it.key} ${it.value} 次" }
            )
            val contentEvents = db.contentEventsInRange(start, end, limit = 200)
            // 样本只取内容事件里的真实标题（抖音/小红书这类），
            // 别再拿 activity_log 的界面文本——那是别的 App 自己的文案，纯噪声。
            val samples = contentEvents
                .map { (if (it.author.isBlank()) "" else it.author + "：") + it.title }
                .filter { it.length > 6 }
                .map { it.take(40) }
                .distinct()
                .take(3)
            if (samples.isNotEmpty()) sb.appendLine("【看到的内容样本】" + samples.joinToString(" / "))

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
        val records = db.recordsInRange(start, end)
        if (records.isEmpty()) {
            sb.appendLine("【消费】今天没有记账")
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
        val (pStart, pEnd) = dayRange(LocalDate.parse(day).minusDays(1).toString())
        val prevTotal = usage.dayUsage(pStart, pEnd, topN = 1).totalMs
        val prevSpend = db.recordsInRange(pStart, pEnd).sumOf { it.amount }
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

        return Stats(sb.toString(), highlights)
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
                return Result(true, day, it.text, it.engine, it.model, it.stats)
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
                db.addDigest(day, "cloud", model, text, stats)
                Result(true, day, text, "cloud", model, stats)
            } catch (e: Exception) {
                // 云端失败不留空档：退回端侧，保证「今天总有一份总结」。
                localGenerate(ctx, db, day, stats, st.highlights)
            }
        }
        return localGenerate(ctx, db, day, stats, st.highlights)
    }

    /**
     * 端侧：**事实段由代码写、模型只补两句**。
     *
     * 为什么不整段交给模型：2.4 GB 的小模型有三个改不掉的毛病——挑重点会漏、
     * 抄数字会错（把 22 写成「二十两」、把 eighteen 写成英文）、建议会说空话。
     * 实测四轮都在补这些洞。改成混合式之后，数字 100% 来自本机统计、看点 100% 覆盖，
     * 模型只负责它最擅长的一段：照着事实给一句观察、一句建议。
     * 模型失败（忙/输出空）时仍然交付事实段 —— 那本来就已经是一份能看的总结了。
     */
    private suspend fun localGenerate(
        ctx: Context,
        db: TraceDbHelper,
        day: String,
        stats: String,
        highlights: List<String>,
    ): Result {
        val model = "MiniCPM-V 4.6 Q4_K_M"
        val facts = factsText(highlights)
        if (facts.isBlank()) {
            // 连看点都挑不出来（今天几乎是空数据），那就没得总结。
            return Result(false, day, "", "local", model, stats, "今天的数据太少，没法总结")
        }

        val ask = facts + "\n\n" +
            "上面是今天的全部事实，已经写好了：不要改动、不要重复、不要改写上面的句子。" +
            "请你在事实后面接着补两句：第一句点出今天最值得注意的一点，第二句给一条针对今天数据的具体建议。" +
            "不要写任何数字（上面的数字已经够了），不要写标题和编号，直接写这两句话。"
        val r = VlmEngine.ask(ctx, ask, maxTokens = POLISH_TOKENS, instruction = POLISH_INSTRUCTION)
        var extra = if (r.ok) clean(r.answer) else ""
        // 小模型偶尔会把事实段整段抄一遍 —— 抄了就丢掉，只保留事实段。
        if (extra.isNotBlank() && (echoesFact(extra, facts) || extra.length > 200)) extra = ""

        val text = if (extra.isBlank()) facts else facts + "\n" + extra
        // 模型没参与时如实标注，不冒名。
        val modelName = if (extra.isBlank()) "本机统计" else model
        db.addDigest(day, "local", modelName, text, stats)
        return Result(true, day, text, "local", modelName, stats)
    }

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

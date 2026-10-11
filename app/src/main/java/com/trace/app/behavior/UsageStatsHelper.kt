package com.trace.app.behavior

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.trace.app.db.TraceDbHelper
import com.trace.app.util.AppNames

class UsageStatsHelper(private val context: Context) {

    /** 某天（[start,end) 区间）的真实使用情况：墙钟总时长、四时段分布、应用时长。 */
    data class DayUsage(
        val totalMs: Long,
        val slots: LongArray,
        val apps: List<AppUsage>,
    ) {
        data class AppUsage(val pkg: String, val name: String, val ms: Long, val opens: Int)
    }

    private fun manager(): UsageStatsManager? =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    fun hasPermission(): Boolean {
        val m = manager() ?: return false
        val now = System.currentTimeMillis()
        val stats = m.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000L, now)
        return stats.isNotEmpty()
    }

    /**
     * 取 [start,end) 区间的系统级使用情况。
     *
     * 为什么不用自己的库表：`behavior_events` 只在采样时写，常常是空的，
     * 于是出现过「屏幕时间总计不到 1 分钟、时段分布却写着 1 小时 35 分」这种自相矛盾。
     * 这里直接读系统 UsageStats 事件流，算出来的总时长/时段/应用是**同源**的。
     *
     * 总时长取所有前后台区间的**并集**（多个应用可能同时算前台，直接相加会重复），
     * 应用时长则是各应用自己的前台累计（可能大于总时长，属正常）。
     */
    fun dayUsage(start: Long, end: Long, topN: Int = 8): DayUsage {
        val m = manager() ?: return DayUsage(0L, LongArray(4), emptyList())
        val evs = m.queryEvents(start, end)
        val e = UsageEvents.Event()
        val raw = ArrayList<Triple<String, Long, Int>>()
        while (evs.hasNextEvent()) {
            evs.getNextEvent(e)
            val t = e.eventType
            if (t == UsageEvents.Event.ACTIVITY_RESUMED ||
                t == UsageEvents.Event.ACTIVITY_PAUSED ||
                t == UsageEvents.Event.ACTIVITY_STOPPED
            ) {
                raw.add(Triple(e.packageName ?: "", e.timeStamp, t))
            }
        }
        raw.sortBy { it.second }

        val open = HashMap<String, Long>()
        val perApp = HashMap<String, Long>()
        val opens = HashMap<String, Int>()
        val intervals = ArrayList<Pair<Long, Long>>()
        for ((pkg, ts, type) in raw) {
            if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                opens[pkg] = (opens[pkg] ?: 0) + 1
                open[pkg] = ts
            } else {
                val st = open.remove(pkg) ?: continue
                if (ts > st) {
                    perApp[pkg] = (perApp[pkg] ?: 0L) + (ts - st)
                    intervals.add(st to ts)
                }
            }
        }
        // 还没关闭的区间算到「现在」为止（当天）或区间末尾（历史某天）
        val tail = minOf(System.currentTimeMillis(), end)
        open.forEach { (pkg, st) ->
            if (tail > st) {
                perApp[pkg] = (perApp[pkg] ?: 0L) + (tail - st)
                intervals.add(st to tail)
            }
        }

        intervals.sortBy { it.first }
        val slots = LongArray(4)
        var total = 0L
        var curS = -1L
        var curE = -1L
        fun flushRange() {
            if (curS < 0 || curE <= curS) return
            val len = curE - curS
            total += len
            slots[hourOf(curS) / 6] += len
        }
        for ((s0, e0) in intervals) {
            when {
                curS < 0 -> { curS = s0; curE = e0 }
                s0 <= curE -> if (e0 > curE) curE = e0
                else -> { flushRange(); curS = s0; curE = e0 }
            }
        }
        flushRange()

        val apps = perApp.entries.sortedByDescending { it.value }.take(topN).map {
            DayUsage.AppUsage(it.key, AppNames.of(context, it.key), it.value, opens[it.key] ?: 0)
        }
        return DayUsage(total, slots, apps)
    }

    private fun hourOf(ts: Long): Int =
        java.util.Calendar.getInstance().apply { timeInMillis = ts }
            .get(java.util.Calendar.HOUR_OF_DAY)

    /**
     * 抓取今日 Top 5 应用使用时长。
     *
     * 返回的 key 是**应用显示名**（「抖音」而不是 `aweme`）：这里原本用
     * `packageName.substringAfterLast('.')`，结果列表里全是 `app` / `zqb` / `read`
     * 这种看不出是什么的尾巴。显示名由 [AppNames] 统一解析（系统标签 + 兜底表）。
     */
    fun todayTopApps(topN: Int = 5): List<Pair<String, Long>> {
        val m = manager() ?: return emptyList()
        val now = System.currentTimeMillis()
        val start = now - 24 * 3600 * 1000L
        val stats = m.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)

        // 同一个包可能被拆成多条（不同 bucket），先按包名合并，避免「同一个应用出现两次」。
        val byPkg = HashMap<String, Long>()
        for (s in stats) {
            if (s.totalTimeInForeground <= 0) continue
            byPkg[s.packageName] = (byPkg[s.packageName] ?: 0L) + s.totalTimeInForeground
        }

        // 再按显示名合并（多个包可能同属一个应用，比如正式版 + 极速版同名时），最后取 Top N。
        val byName = LinkedHashMap<String, Long>()
        byPkg.entries.sortedByDescending { it.value }.forEach { (pkg, ms) ->
            val name = AppNames.of(context, pkg)
            byName[name] = (byName[name] ?: 0L) + ms
        }
        return byName.entries
            .sortedByDescending { it.value }
            .take(topN)
            .map { it.key to it.value }
    }

    /** 写入模拟数据，用于无权限场景下也能跑通 */
    fun insertSampleBehavior(dbHelper: TraceDbHelper) {
        if (dbHelper.hasAnyBehavior()) return
        val now = System.currentTimeMillis()
        val samples = listOf(
            Triple("com.tencent.mm", 72 * 60 * 1000L, now - 5 * 3600_000L),
            Triple("com.ss.android.ugc.aweme", 56 * 60 * 1000L, now - 6 * 3600_000L),
            Triple("com.taobao.taobao", 28 * 60 * 1000L, now - 8 * 3600_000L),
            Triple("tv.danmaku.bili", 18 * 60 * 1000L, now - 9 * 3600_000L),
            Triple("com.eg.android.AlipayGphone", 12 * 60 * 1000L, now - 10 * 3600_000L)
        )
        samples.forEach { (pkg, dur, _) -> dbHelper.addBehavior(pkg, dur) }
    }
}

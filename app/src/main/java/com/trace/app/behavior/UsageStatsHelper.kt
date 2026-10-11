package com.trace.app.behavior

import android.app.usage.UsageStatsManager
import android.content.Context
import com.trace.app.db.TraceDbHelper
import com.trace.app.util.AppNames

class UsageStatsHelper(private val context: Context) {

    private fun manager(): UsageStatsManager? =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    fun hasPermission(): Boolean {
        val m = manager() ?: return false
        val now = System.currentTimeMillis()
        val stats = m.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000L, now)
        return stats.isNotEmpty()
    }

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

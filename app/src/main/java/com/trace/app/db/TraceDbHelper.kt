package com.trace.app.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.trace.app.model.Record

class TraceDbHelper(context: Context) : SQLiteOpenHelper(context, "trace.db", null, 5) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                amount REAL NOT NULL,
                category TEXT NOT NULL,
                merchant TEXT,
                note TEXT,
                timestamp INTEGER NOT NULL,
                is_night INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE behavior_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                package_name TEXT,
                duration_ms INTEGER NOT NULL,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(createUsageEventsSql())
        db.execSQL(createScreenInsightsSql())
        db.execSQL(createFramesSql())
        db.execSQL(createContentEventsSql())
        db.execSQL(createActivityLogSql())
        db.execSQL(createUsageIndexSql())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(createUsageEventsSql())
        }
        if (oldVersion < 3) {
            db.execSQL(createScreenInsightsSql())
        }
        if (oldVersion < 4) {
            db.execSQL(createFramesSql())
            db.execSQL(createContentEventsSql())
        }
        if (oldVersion < 5) {
            db.execSQL(createActivityLogSql())
            db.execSQL(createUsageIndexSql())
        }
    }

    /**
     * 「用户在某应用里具体在干什么」（v5 新增）。
     *
     * 与 usage_events 的区别：usage_events 是原始事件流（谁 RESUMED/PAUSED 了），
     * 这张表是**给人看的结论**——what 是中文短语（在刷视频 / 在看商品 / 在聊天），
     * detail 是当时页面的标题/作者，source 记录这句话是推算的（activity 类名）
     * 还是读语义树读出来的（a11y）。
     */
    private fun createActivityLogSql(): String =
        """
        CREATE TABLE IF NOT EXISTS activity_log (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp INTEGER NOT NULL,
            package_name TEXT,
            activity TEXT,
            what TEXT,
            detail TEXT,
            source TEXT
        )
        """.trimIndent()

    /** 按包名取最近一条事件是常用查询（每次采样都要问「这个应用现在停在哪个页面」）。 */
    private fun createUsageIndexSql(): String =
        "CREATE INDEX IF NOT EXISTS idx_usage_pkg_ts ON usage_events(package_name, timestamp)"

    /**
     * 关键帧存档。截一帧很便宜（几十 ms），跑模型很贵（15-20 s）——
     * 所以触发时先把帧存下来（analyzed=0），模型分析可以延后到空闲/充电时批量做。
     */
    private fun createFramesSql(): String =
        """
        CREATE TABLE IF NOT EXISTS frames (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp INTEGER NOT NULL,
            package_name TEXT,
            trigger TEXT,
            jpeg_path TEXT,
            analyzed INTEGER DEFAULT 0,
            insight_id INTEGER
        )
        """.trimIndent()

    /**
     * 内容级事件（来自无障碍语义，不跑模型、零成本）：
     * VIDEO_CHANGED / LIKE / UNLIKE / SAVE / UNSAVE。
     */
    private fun createContentEventsSql(): String =
        """
        CREATE TABLE IF NOT EXISTS content_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp INTEGER NOT NULL,
            package_name TEXT,
            event TEXT,
            title TEXT,
            author TEXT,
            extra TEXT
        )
        """.trimIndent()

    private fun createScreenInsightsSql(): String =
        """
        CREATE TABLE IF NOT EXISTS screen_insights (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp INTEGER NOT NULL,
            package_name TEXT,
            activity TEXT,
            slice INTEGER NOT NULL,
            prefill_ms INTEGER NOT NULL,
            gen_ms INTEGER NOT NULL,
            answer TEXT
        )
        """.trimIndent()

    private fun createUsageEventsSql(): String =
        """
        CREATE TABLE IF NOT EXISTS usage_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp INTEGER NOT NULL,
            package_name TEXT,
            activity TEXT,
            event_type TEXT
        )
        """.trimIndent()

    fun addRecord(amount: Double, category: String, merchant: String?, note: String?): Long {
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val isNight = if (hour >= 23 || hour < 3) 1 else 0
        val values = ContentValues().apply {
            put("amount", amount)
            put("category", category)
            put("merchant", merchant)
            put("note", note)
            put("timestamp", System.currentTimeMillis())
            put("is_night", isNight)
        }
        return writableDatabase.insert("records", null, values)
    }

    fun allRecords(): List<Record> {
        val list = mutableListOf<Record>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, amount, category, merchant, note, timestamp, is_night FROM records ORDER BY timestamp DESC",
            null
        )
        while (cursor.moveToNext()) {
            list.add(
                Record(
                    id = cursor.getLong(0),
                    amount = cursor.getDouble(1),
                    category = cursor.getString(2) ?: "其他",
                    merchant = cursor.getString(3),
                    note = cursor.getString(4),
                    timestamp = cursor.getLong(5),
                    isNight = cursor.getInt(6) == 1
                )
            )
        }
        cursor.close()
        return list
    }

    fun totalAmount(): Double = allRecords().sumOf { it.amount }

    fun nightCount(): Int = allRecords().count { it.isNight }

    fun addBehavior(packageName: String?, durationMs: Long) {
        val values = ContentValues().apply {
            put("package_name", packageName)
            put("duration_ms", durationMs)
            put("timestamp", System.currentTimeMillis())
        }
        writableDatabase.insert("behavior_events", null, values)
    }

    fun behaviorByDay(daysAgo: Int = 0): List<Pair<String, Long>> {
        val list = mutableListOf<Pair<String, Long>>()
        val startTs = System.currentTimeMillis() - daysAgo * 24 * 3600 * 1000L
        val cursor = readableDatabase.rawQuery(
            "SELECT package_name, SUM(duration_ms) FROM behavior_events WHERE timestamp > ? GROUP BY package_name ORDER BY 2 DESC LIMIT 5",
            arrayOf(startTs.toString())
        )
        while (cursor.moveToNext()) {
            list.add((cursor.getString(0) ?: "unknown") to cursor.getLong(1))
        }
        cursor.close()
        return list
    }

    fun hasAnyBehavior(): Boolean {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM behavior_events", null)
        cursor.moveToFirst()
        val n = cursor.getInt(0)
        cursor.close()
        return n > 0
    }

    // ===== usage_events（MonitorService 实时采集）=====

    fun addUsageEvent(ts: Long, pkg: String, activity: String, type: String) {
        val values = ContentValues().apply {
            put("timestamp", ts)
            put("package_name", pkg)
            put("activity", activity)
            put("event_type", type)
        }
        writableDatabase.insert("usage_events", null, values)
    }

    fun lastUsageEventTs(): Long {
        val cursor = readableDatabase.rawQuery(
            "SELECT MAX(timestamp) FROM usage_events", null
        )
        cursor.moveToFirst()
        val v = cursor.getLong(0)
        cursor.close()
        return v
    }

    fun usageEventCount(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM usage_events", null)
        cursor.moveToFirst()
        val n = cursor.getInt(0)
        cursor.close()
        return n
    }

    data class UsageEvent(
        val timestamp: Long,
        val pkg: String,
        val activity: String,
        val type: String
    )

    fun recentUsageEvents(limit: Int = 20): List<UsageEvent> {
        val list = mutableListOf<UsageEvent>()
        val cursor = readableDatabase.rawQuery(
            "SELECT timestamp, package_name, activity, event_type FROM usage_events ORDER BY timestamp DESC LIMIT ?",
            arrayOf(limit.toString())
        )
        while (cursor.moveToNext()) {
            list.add(
                UsageEvent(
                    timestamp = cursor.getLong(0),
                    pkg = cursor.getString(1) ?: "?",
                    activity = (cursor.getString(2) ?: "").substringAfterLast('.'),
                    type = cursor.getString(3) ?: "?"
                )
            )
        }
        cursor.close()
        return list
    }

    // ===== screen_insights（VlmCaptureService 端侧屏幕理解）=====

    data class ScreenInsight(
        val timestamp: Long,
        val pkg: String,
        val slice: Int,
        val prefillMs: Long,
        val genMs: Long,
        val answer: String
    )

    fun addScreenInsight(
        pkg: String,
        activity: String,
        slice: Int,
        prefillMs: Long,
        genMs: Long,
        answer: String
    ): Long {
        val values = ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("package_name", pkg)
            put("activity", activity)
            put("slice", slice)
            put("prefill_ms", prefillMs)
            put("gen_ms", genMs)
            put("answer", answer)
        }
        return writableDatabase.insert("screen_insights", null, values)
    }

    fun screenInsightCount(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM screen_insights", null)
        cursor.moveToFirst()
        val n = cursor.getInt(0)
        cursor.close()
        return n
    }

    /** 某个包最近一次的端侧理解结果（用于切片自适应的反馈）。 */
    fun lastScreenInsightFor(pkg: String): ScreenInsight? {
        val cursor = readableDatabase.rawQuery(
            "SELECT timestamp, package_name, slice, prefill_ms, gen_ms, answer FROM screen_insights " +
                "WHERE package_name = ? ORDER BY timestamp DESC LIMIT 1",
            arrayOf(pkg)
        )
        val result = if (cursor.moveToFirst()) {
            ScreenInsight(
                timestamp = cursor.getLong(0),
                pkg = cursor.getString(1) ?: "?",
                slice = cursor.getInt(2),
                prefillMs = cursor.getLong(3),
                genMs = cursor.getLong(4),
                answer = cursor.getString(5) ?: ""
            )
        } else {
            null
        }
        cursor.close()
        return result
    }

    fun recentScreenInsights(limit: Int = 20): List<ScreenInsight> {
        val list = mutableListOf<ScreenInsight>()
        val cursor = readableDatabase.rawQuery(
            "SELECT timestamp, package_name, slice, prefill_ms, gen_ms, answer FROM screen_insights " +
                "ORDER BY timestamp DESC LIMIT ?",
            arrayOf(limit.toString())
        )
        while (cursor.moveToNext()) {
            list.add(
                ScreenInsight(
                    timestamp = cursor.getLong(0),
                    pkg = cursor.getString(1) ?: "?",
                    slice = cursor.getInt(2),
                    prefillMs = cursor.getLong(3),
                    genMs = cursor.getLong(4),
                    answer = cursor.getString(5) ?: ""
                )
            )
        }
        cursor.close()
        return list
    }

    // ---------------------------------------------------------------- 关键帧存档

    data class PendingFrame(
        val id: Long,
        val timestamp: Long,
        val pkg: String,
        val trigger: String,
        val jpegPath: String?,
    )

    fun addFrame(timestamp: Long, pkg: String?, trigger: String, jpegPath: String?): Long {
        val values = ContentValues().apply {
            put("timestamp", timestamp)
            put("package_name", pkg)
            put("trigger", trigger)
            put("jpeg_path", jpegPath)
            put("analyzed", 0)
        }
        return writableDatabase.insert("frames", null, values)
    }

    fun frameCount(): Int = countWhere("frames", null, null)

    fun pendingFrameCount(): Int = countWhere("frames", "analyzed = 0", null)

    fun pendingFrames(limit: Int = 20): List<PendingFrame> {
        val list = mutableListOf<PendingFrame>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, timestamp, package_name, trigger, jpeg_path FROM frames " +
                "WHERE analyzed = 0 ORDER BY timestamp ASC LIMIT ?",
            arrayOf(limit.toString())
        )
        while (cursor.moveToNext()) {
            list.add(
                PendingFrame(
                    id = cursor.getLong(0),
                    timestamp = cursor.getLong(1),
                    pkg = cursor.getString(2) ?: "?",
                    trigger = cursor.getString(3) ?: "?",
                    jpegPath = cursor.getString(4),
                )
            )
        }
        cursor.close()
        return list
    }

    fun markFrameAnalyzed(id: Long, insightId: Long) {
        writableDatabase.execSQL(
            "UPDATE frames SET analyzed = 1, insight_id = ? WHERE id = ?",
            arrayOf<Any>(insightId, id)
        )
    }

    // ---------------------------------------------------------------- 内容事件

    data class ContentEvent(
        val id: Long,
        val timestamp: Long,
        val pkg: String,
        val event: String,
        val title: String,
        val author: String,
        val extra: String,
    )

    fun addContentEvent(
        timestamp: Long,
        pkg: String?,
        event: String,
        title: String?,
        author: String?,
        extra: String?,
    ): Long {
        val values = ContentValues().apply {
            put("timestamp", timestamp)
            put("package_name", pkg)
            put("event", event)
            put("title", title)
            put("author", author)
            put("extra", extra)
        }
        return writableDatabase.insert("content_events", null, values)
    }

    fun contentEventCount(): Int = countWhere("content_events", null, null)

    fun contentEventCountOf(event: String): Int =
        countWhere("content_events", "event = ?", arrayOf(event))

    fun recentContentEvents(limit: Int = 30): List<ContentEvent> {
        val list = mutableListOf<ContentEvent>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, timestamp, package_name, event, title, author, extra FROM content_events " +
                "ORDER BY timestamp DESC LIMIT ?",
            arrayOf(limit.toString())
        )
        while (cursor.moveToNext()) {
            list.add(
                ContentEvent(
                    id = cursor.getLong(0),
                    timestamp = cursor.getLong(1),
                    pkg = cursor.getString(2) ?: "?",
                    event = cursor.getString(3) ?: "?",
                    title = cursor.getString(4) ?: "",
                    author = cursor.getString(5) ?: "",
                    extra = cursor.getString(6) ?: "",
                )
            )
        }
        cursor.close()
        return list
    }

    // ---------------------------------------------------------------- 应用内行为「在干什么」

    data class ActivityLog(
        val id: Long,
        val timestamp: Long,
        val pkg: String,
        val activity: String,
        val what: String,
        val detail: String,
        val source: String,
    )

    fun addActivityLog(
        timestamp: Long,
        pkg: String?,
        activity: String?,
        what: String?,
        detail: String?,
        source: String?,
    ): Long {
        val values = ContentValues().apply {
            put("timestamp", timestamp)
            put("package_name", pkg)
            put("activity", activity)
            put("what", what)
            put("detail", detail)
            put("source", source)
        }
        return writableDatabase.insert("activity_log", null, values)
    }

    fun activityLogCount(): Int = countWhere("activity_log", null, null)

    fun recentActivityLog(limit: Int = 30): List<ActivityLog> {
        val list = mutableListOf<ActivityLog>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, timestamp, package_name, activity, what, detail, source FROM activity_log " +
                "ORDER BY timestamp DESC LIMIT ?",
            arrayOf(limit.toString())
        )
        while (cursor.moveToNext()) {
            list.add(
                ActivityLog(
                    id = cursor.getLong(0),
                    timestamp = cursor.getLong(1),
                    pkg = cursor.getString(2) ?: "?",
                    activity = cursor.getString(3) ?: "",
                    what = cursor.getString(4) ?: "",
                    detail = cursor.getString(5) ?: "",
                    source = cursor.getString(6) ?: "",
                )
            )
        }
        cursor.close()
        return list
    }

    /** 某个包最近一次被记录到的 Activity 全类名（MonitorService 写进 usage_events 的那一列）。 */
    fun lastActivityFor(pkg: String): String? {
        val cursor = readableDatabase.rawQuery(
            "SELECT activity FROM usage_events WHERE package_name = ? ORDER BY timestamp DESC LIMIT 1",
            arrayOf(pkg)
        )
        val v = if (cursor.moveToFirst()) cursor.getString(0) else null
        cursor.close()
        return v
    }

    private fun countWhere(table: String, where: String?, args: Array<String>?): Int {
        val sql = "SELECT COUNT(*) FROM $table" + if (where == null) "" else " WHERE $where"
        val cursor = readableDatabase.rawQuery(sql, args)
        cursor.moveToFirst()
        val n = cursor.getInt(0)
        cursor.close()
        return n
    }

    fun debugDump(): String {
        val sb = StringBuilder()
        sb.appendLine("=== records ===")
        allRecords().forEach {
            sb.appendLine("  #${it.id} ¥${it.amount} ${it.category} night=${it.isNight} note=${it.note}")
        }
        sb.appendLine("=== behavior_events (top 5) ===")
        behaviorByDay(7).forEach {
            sb.appendLine("  ${it.first}: ${it.second / 60000} min")
        }
        return sb.toString()
    }
}

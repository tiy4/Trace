package com.trace.app.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.trace.app.model.Record

class TraceDbHelper(context: Context) : SQLiteOpenHelper(context, "trace.db", null, 7) {

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
        db.execSQL(createDailyDigestSql())
        db.execSQL(createChatMessageSql())
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
        if (oldVersion < 6) {
            db.execSQL(createDailyDigestSql())
            db.execSQL(createChatMessageSql())
        }
        if (oldVersion < 7) {
            // v7：总结的字段化版本（{yihua, jianyi}），给卡片分区渲染与以后做周报聚合用。
            db.execSQL("ALTER TABLE daily_digest ADD COLUMN json TEXT")
        }
    }

    /**
     * 每日 AI 总结留档（v6）。
     *
     * 一天可以有多份（重新生成会追加一条，读的时候取最新），这样能回看"模型当时是怎么说的"，
     * 也能对比不同提示词/模型的效果。stats 存生成时喂给模型的关键数字，出问题时不用猜。
     */
    private fun createDailyDigestSql(): String =
        """
        CREATE TABLE IF NOT EXISTS daily_digest (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            day TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            engine TEXT,
            model TEXT,
            text TEXT NOT NULL,
            stats TEXT,
            json TEXT
        )
        """.trimIndent()

    /** AI 助手聊天留档（v6）：关掉页面也不丢，可回看。 */
    private fun createChatMessageSql(): String =
        """
        CREATE TABLE IF NOT EXISTS chat_messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp INTEGER NOT NULL,
            role TEXT NOT NULL,
            text TEXT NOT NULL,
            engine TEXT
        )
        """.trimIndent()

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

    // ---------------------------------------------------------------- 每日总结 / 聊天留档（v6）

    data class Digest(
        val id: Long,
        val day: String,
        val createdAt: Long,
        val engine: String,
        val model: String,
        val text: String,
        val stats: String,
        /** v7：字段化总结（JSON 文本，形如 {"yihua":…,"jianyi":…}）；老数据为空串。 */
        val json: String,
    )

    fun addDigest(
        day: String,
        engine: String,
        model: String,
        text: String,
        stats: String,
        json: String,
    ): Long {
        val values = ContentValues().apply {
            put("day", day)
            put("created_at", System.currentTimeMillis())
            put("engine", engine)
            put("model", model)
            put("text", text)
            put("stats", stats)
            put("json", json)
        }
        return writableDatabase.insert("daily_digest", null, values)
    }

    private fun readDigest(cursor: android.database.Cursor) = Digest(
        id = cursor.getLong(0),
        day = cursor.getString(1) ?: "",
        createdAt = cursor.getLong(2),
        engine = cursor.getString(3) ?: "",
        model = cursor.getString(4) ?: "",
        text = cursor.getString(5) ?: "",
        stats = cursor.getString(6) ?: "",
        json = cursor.getString(7) ?: "",
    )

    /** 某天最新的一份总结。 */
    fun latestDigest(day: String): Digest? {
        val cursor = readableDatabase.rawQuery(
            "SELECT id, day, created_at, engine, model, text, stats, json FROM daily_digest " +
                "WHERE day = ? ORDER BY created_at DESC LIMIT 1",
            arrayOf(day)
        )
        val d = if (cursor.moveToFirst()) readDigest(cursor) else null
        cursor.close()
        return d
    }

    fun recentDigests(limit: Int = 14): List<Digest> {
        val list = mutableListOf<Digest>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, day, created_at, engine, model, text, stats, json FROM daily_digest " +
                "ORDER BY created_at DESC LIMIT ?",
            arrayOf(limit.toString())
        )
        while (cursor.moveToNext()) list.add(readDigest(cursor))
        cursor.close()
        return list
    }

    fun digestCount(): Int = countWhere("daily_digest", null, null)

    /**
     * 从某天起（含）的所有总结，按天倒序、同一天里最新的在前。
     *
     * 日历页用它做两件事：① 标记「哪几天有总结」；② 点某天时直接取那天的正文。
     * `day` 是 'yyyy-MM-dd' 文本，做范围比较是安全的（字典序 == 日期序）。
     */
    fun digestsSince(fromDay: String, limit: Int = 400): List<Digest> {
        val list = mutableListOf<Digest>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, day, created_at, engine, model, text, stats, json FROM daily_digest " +
                "WHERE day >= ? ORDER BY day DESC, created_at DESC LIMIT ?",
            arrayOf(fromDay, limit.toString())
        )
        while (cursor.moveToNext()) list.add(readDigest(cursor))
        cursor.close()
        return list
    }

    /** 有总结的所有日期（倒序），给日历点小圆点用。 */
    fun digestDays(limit: Int = 400): List<String> = digestsSince("0000-01-01", limit).map { it.day }

    data class ChatMessage(
        val id: Long,
        val timestamp: Long,
        val role: String,
        val text: String,
        val engine: String,
    )

    fun addChatMessage(role: String, text: String, engine: String): Long {
        val values = ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("role", role)
            put("text", text)
            put("engine", engine)
        }
        return writableDatabase.insert("chat_messages", null, values)
    }

    /** 最近 N 条聊天记录，**按时间正序**返回（可直接铺进气泡）。 */
    fun recentChatMessages(limit: Int = 60): List<ChatMessage> {
        val list = mutableListOf<ChatMessage>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, timestamp, role, text, engine FROM chat_messages ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        )
        while (cursor.moveToNext()) {
            list.add(
                ChatMessage(
                    id = cursor.getLong(0),
                    timestamp = cursor.getLong(1),
                    role = cursor.getString(2) ?: "ai",
                    text = cursor.getString(3) ?: "",
                    engine = cursor.getString(4) ?: "",
                )
            )
        }
        cursor.close()
        return list.reversed()
    }

    fun clearChatMessages() {
        writableDatabase.delete("chat_messages", null, null)
    }

    fun chatMessageCount(): Int = countWhere("chat_messages", null, null)

    // ---------------------------------------------------------------- 区间聚合（每日总结用）

    /** [start, end) 内按应用汇总的屏幕时长（behavior_events 是采样表）。 */
    fun usageInRange(start: Long, end: Long, limit: Int = 8): List<Pair<String, Long>> {
        val list = mutableListOf<Pair<String, Long>>()
        val cursor = readableDatabase.rawQuery(
            "SELECT package_name, SUM(duration_ms) FROM behavior_events WHERE timestamp >= ? AND timestamp < ? " +
                "GROUP BY package_name ORDER BY 2 DESC LIMIT ?",
            arrayOf(start.toString(), end.toString(), limit.toString())
        )
        while (cursor.moveToNext()) {
            list.add((cursor.getString(0) ?: "unknown") to cursor.getLong(1))
        }
        cursor.close()
        return list
    }

    fun totalUsageMs(start: Long, end: Long): Long {
        val cursor = readableDatabase.rawQuery(
            "SELECT IFNULL(SUM(duration_ms), 0) FROM behavior_events WHERE timestamp >= ? AND timestamp < ?",
            arrayOf(start.toString(), end.toString())
        )
        val v = if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        cursor.close()
        return v
    }

    fun usageEventsInRange(start: Long, end: Long, limit: Int = 400): List<UsageEvent> {
        val list = mutableListOf<UsageEvent>()
        val cursor = readableDatabase.rawQuery(
            "SELECT timestamp, package_name, activity, event_type FROM usage_events " +
                "WHERE timestamp >= ? AND timestamp < ? ORDER BY timestamp ASC LIMIT ?",
            arrayOf(start.toString(), end.toString(), limit.toString())
        )
        while (cursor.moveToNext()) {
            list.add(
                UsageEvent(
                    timestamp = cursor.getLong(0),
                    pkg = cursor.getString(1) ?: "?",
                    activity = cursor.getString(2) ?: "",
                    type = cursor.getString(3) ?: "",
                )
            )
        }
        cursor.close()
        return list
    }

    /** 内容事件计数（LIKE / UNLIKE / SAVE / UNSAVE / VIDEO_CHANGED）。 */
    fun contentEventCounts(start: Long, end: Long): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        val cursor = readableDatabase.rawQuery(
            "SELECT event, COUNT(*) FROM content_events WHERE timestamp >= ? AND timestamp < ? GROUP BY event",
            arrayOf(start.toString(), end.toString())
        )
        while (cursor.moveToNext()) map[cursor.getString(0) ?: "?"] = cursor.getInt(1)
        cursor.close()
        return map
    }

    /** 内容事件明细（用来给总结提供"刷到了什么"的样本）。 */
    fun contentEventsInRange(start: Long, end: Long, limit: Int = 60): List<ContentEvent> {
        val list = mutableListOf<ContentEvent>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, timestamp, package_name, event, title, author, extra FROM content_events " +
                "WHERE timestamp >= ? AND timestamp < ? ORDER BY timestamp ASC LIMIT ?",
            arrayOf(start.toString(), end.toString(), limit.toString())
        )
        while (cursor.moveToNext()) {
            list.add(
                ContentEvent(
                    id = cursor.getLong(0),
                    timestamp = cursor.getLong(1),
                    pkg = cursor.getString(2) ?: "",
                    event = cursor.getString(3) ?: "",
                    title = cursor.getString(4) ?: "",
                    author = cursor.getString(5) ?: "",
                    extra = cursor.getString(6) ?: "",
                )
            )
        }
        cursor.close()
        return list
    }

    fun recordsInRange(start: Long, end: Long): List<Record> {
        val list = mutableListOf<Record>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, amount, category, merchant, note, timestamp, is_night FROM records " +
                "WHERE timestamp >= ? AND timestamp < ? ORDER BY timestamp ASC",
            arrayOf(start.toString(), end.toString())
        )
        while (cursor.moveToNext()) {
            list.add(
                Record(
                    id = cursor.getLong(0),
                    amount = cursor.getDouble(1),
                    category = cursor.getString(2) ?: "",
                    merchant = cursor.getString(3),
                    note = cursor.getString(4),
                    timestamp = cursor.getLong(5),
                    isNight = cursor.getInt(6) == 1,
                )
            )
        }
        cursor.close()
        return list
    }

    fun activityLogInRange(start: Long, end: Long, limit: Int = 80): List<ActivityLog> {
        val list = mutableListOf<ActivityLog>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, timestamp, package_name, activity, what, detail, source FROM activity_log " +
                "WHERE timestamp >= ? AND timestamp < ? ORDER BY timestamp ASC LIMIT ?",
            arrayOf(start.toString(), end.toString(), limit.toString())
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

    /** 某天记了几笔账（判断"今天有没有数据"用）。 */
    fun recordCountInRange(start: Long, end: Long): Int {
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM records WHERE timestamp >= ? AND timestamp < ?",
            arrayOf(start.toString(), end.toString())
        )
        val n = if (cursor.moveToFirst()) cursor.getInt(0) else 0
        cursor.close()
        return n
    }

    fun frameCountInRange(start: Long, end: Long): Int {        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM frames WHERE timestamp >= ? AND timestamp < ?",
            arrayOf(start.toString(), end.toString())
        )
        val n = if (cursor.moveToFirst()) cursor.getInt(0) else 0
        cursor.close()
        return n
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

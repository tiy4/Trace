package com.trace.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.trace.app.behavior.ActivityLabel
import com.trace.app.behavior.UsageStatsHelper
import com.trace.app.db.TraceDbHelper
import com.trace.app.util.AppNames
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 行为统计页。
 *
 * 这一页以前的三个问题是同一类：**给用户看的是工程内部产物**——
 *   • Top 5 打的是 `pkg.substringAfterLast('.')`（`zqb` / `app` / `read`）
 *   • 行为流打的是 `包名末段 · Activity 类名 [RESUMED]`
 *   • 于是「用户到底在干什么」这件事在页面上根本读不出来
 * 现在统一收口到 [AppNames]（包名 → 中文应用名）与 [ActivityLabel]（类名 → 在干什么）。
 */
class BehaviorActivity : AppCompatActivity() {

    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_behavior)

        val db = TraceDbHelper(this)
        val ush = UsageStatsHelper(this)

        val has = ush.hasPermission()
        val btnGrant = findViewById<Button>(R.id.btnGrant)
        if (has) {
            btnGrant.visibility = Button.GONE
        } else {
            btnGrant.setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            // 没权限时灌入演示数据，让 UI 不空
            ush.insertSampleBehavior(db)
        }

        val top = ush.todayTopApps()
        val tv = findViewById<TextView>(R.id.tvTop)
        val header = if (has) "✅ 已授权 · 实时 UsageStats 数据\n" else "🔒 未授权 · 演示数据\n"
        tv.text = if (top.isNotEmpty()) {
            header + top.joinToString("\n") { (name, ms) -> "• $name : ${fmtDuration(ms)}" }
        } else {
            val fromDb = db.behaviorByDay(7)
            if (fromDb.isEmpty()) {
                "没有行为数据。\n请在 vivo 设置中授予「使用情况访问」权限后返回。"
            } else {
                header + fromDb.joinToString("\n") { (pkg, ms) ->
                    "• ${AppNames.of(this, pkg)} : ${fmtDuration(ms)}"
                }
            }
        }

        // ---- 应用内行为：把「停在哪一屏」和「内容事件」合成一条时间线 ----
        // 前者来自无障碍/Activity（每换一屏记一条），后者来自内容通道（换视频/点赞/收藏）。
        // 两条合起来才回答得了「我在抖音/微信里到底干了什么」。
        val rows = ArrayList<WhatRow>()
        db.recentActivityLog(20).forEach { w ->
            rows.add(WhatRow(w.timestamp, AppNames.of(this, w.pkg), w.what, w.detail.take(40)))
        }
        db.recentContentEvents(20).forEach { c ->
            val d = listOf(c.title, c.author).filter { it.isNotBlank() }.distinct()
                .joinToString(" · ")
            rows.add(WhatRow(c.timestamp, AppNames.of(this, c.pkg), contentLabel(c.event), d.take(40)))
        }
        rows.sortByDescending { it.ts }
        val topRows = rows.take(25)
        val tvWhat = findViewById<TextView>(R.id.tvWhat)
        tvWhat.text = if (topRows.isEmpty()) {
            "还没有记录。\n开启「行为监控」后，Trace 会在这里记下「在哪个应用 · 在干什么」" +
                "（只看当前页面是哪一屏，不截屏、不跑模型）。"
        } else {
            val lines = topRows.joinToString("\n") { r ->
                // 「设置 · 设置」这种重复没必要留
                val what = if (r.what == r.name || r.what.isBlank()) "" else " · ${r.what}"
                val detail = if (r.detail.isBlank()) "" else "\n        ${r.detail}"
                "${sdf.format(Date(r.ts))}  ${r.name}$what$detail"
            }
            "应用内行为 ${db.activityLogCount()} 条 · 内容事件 ${db.contentEventCount()} 条" +
                " · 最近 ${topRows.size} 条：\n\n$lines"
        }

        // ---- 原始事件流（监控服务采集），同样翻译成人话 ----
        val events = db.recentUsageEvents(15)
        val tvEvents = findViewById<TextView>(R.id.tvEvents)
        tvEvents.text = if (events.isEmpty()) {
            "监控未开启，暂无实时事件。回首页点「开启行为监控」。"
        } else {
            val total = db.usageEventCount()
            val lines = events.joinToString("\n") { e ->
                val name = AppNames.of(this, e.pkg)
                val what = ActivityLabel.describe(e.pkg, e.activity, null)
                // 类名实在猜不出动作时才把原始类名附上，方便对照（否则一律不暴露内部名）。
                val raw = if (what == "使用中" && e.activity.isNotBlank()) "（${e.activity}）" else ""
                "${sdf.format(Date(e.timestamp))}  $name · $what$raw  [${typeLabel(e.type)}]"
            }
            "共 $total 条 · 最近 ${events.size} 条：\n\n$lines"
        }
    }

    /** 时间线里的一行：可能来自「停在哪一屏」，也可能来自内容通道的一个动作。 */
    private data class WhatRow(val ts: Long, val name: String, val what: String, val detail: String)

    /** 内容通道的事件名 → 人话。 */
    private fun contentLabel(e: String): String = when (e) {
        "VIDEO_CHANGED" -> "换了个视频"
        "LIKE" -> "点赞"
        "UNLIKE" -> "取消点赞"
        "SAVE" -> "收藏"
        "UNSAVE" -> "取消收藏"
        else -> e
    }

    /** `RESUMED` 这类内部事件名也翻译掉。 */
    private fun typeLabel(t: String): String = when (t) {
        "RESUMED" -> "进入"
        "PAUSED" -> "离开"
        "SCREEN_OFF" -> "熄屏"
        else -> t
    }

    private fun fmtDuration(ms: Long): String {
        val min = ms / 60_000L
        return when {
            min >= 60 -> "${min / 60} 小时 ${min % 60} 分"
            min >= 1 -> "$min 分钟"
            else -> "<1 分钟"
        }
    }
}

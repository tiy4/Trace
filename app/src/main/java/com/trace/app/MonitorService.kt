package com.trace.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.trace.app.db.TraceDbHelper

/**
 * 行为监控前台服务：
 * - 每 15 秒增量拉取 UsageEvents（前台切换事件）写入本地 SQLite
 * - 通知栏常驻开关：游戏模式 = 暂停采集
 * - 游戏包名自动识别：打游戏时不记录（自动游戏模式）
 */
class MonitorService : Service() {

    companion object {
        const val ACTION_PAUSE = "com.trace.app.ACTION_PAUSE"
        const val ACTION_RESUME = "com.trace.app.ACTION_RESUME"
        const val ACTION_STOP = "com.trace.app.ACTION_STOP"
        const val CHANNEL_ID = "trace_monitor"
        const val NOTIFY_ID = 1001

        // 常见游戏包名：游戏模式下不记录（隐私：游戏行为与消费无关）
        val GAME_PACKAGES = setOf(
            "com.tencent.tmgp.sgame",     // 王者荣耀
            "com.tencent.tmgp.yys.zqb",   // 阴阳师（zqb）
            "com.netease.dwrg",           // 蛋仔派对
            "com.miHoYo.Yuanshen",        // 原神
            "com.miHoYo.hyp",             // 米游社启动器
            "com.tencent.lolm",           // 英雄联盟手游
            "com.netease.mrzh"            // 梦幻诛仙
        )

        fun isGame(pkg: String?): Boolean {
            if (pkg.isNullOrEmpty()) return false
            return GAME_PACKAGES.contains(pkg) || pkg.startsWith("com.tencent.tmgp.")
        }

        // 服务是否在跑（给 UI 用）
        @Volatile
        var running: Boolean = false
            private set
    }

    @Volatile
    private var paused = false
    private lateinit var db: TraceDbHelper
    private lateinit var handler: Handler
    private lateinit var thread: HandlerThread
    private var lastTs = 0L

    private val poll = object : Runnable {
        override fun run() {
            if (!paused) {
                try {
                    collect()
                } catch (e: Exception) {
                    android.util.Log.e("TraceMonitor", "collect failed", e)
                }
            }
            handler.postDelayed(this, 15_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = TraceDbHelper(this)
        lastTs = db.lastUsageEventTs()
        if (lastTs <= 0) lastTs = System.currentTimeMillis() - 60_000
        thread = HandlerThread("trace-monitor").apply { start() }
        handler = Handler(thread.looper)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                paused = true
                updateNotification()
            }
            ACTION_RESUME -> {
                paused = false
                lastTs = System.currentTimeMillis() // 暂停期间不回捞
                updateNotification()
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        running = true
        startAsForeground()

        if (intent?.action == null) {
            // 首次启动，立即采集一次
            handler.post(poll)
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFY_ID, n)
        }
    }

    private fun collect() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(lastTs, now)
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val type = when (ev.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> "RESUMED"
                UsageEvents.Event.ACTIVITY_PAUSED -> "PAUSED"
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> "SCREEN_OFF"
                else -> null
            } ?: continue
            // 不记录自己；游戏自动跳过（自动游戏模式）
            if (ev.packageName == packageName) continue
            if (isGame(ev.packageName)) continue
            db.addUsageEvent(ev.timeStamp, ev.packageName, ev.className ?: "", type)
        }
        lastTs = now
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (paused) {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_pause)
                .setContentTitle("Trace · 已暂停（游戏模式）")
                .setContentText("行为采集中断中 · 恢复后继续记录")
                .setContentIntent(openApp)
                .addAction(0, "▶ 恢复记录", actionPi(ACTION_RESUME))
                .addAction(0, "⏹ 停止监控", actionPi(ACTION_STOP))
        } else {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Trace · 行为记录中")
                .setContentText("正在记录应用使用行为 · 打游戏可开游戏模式")
                .setContentIntent(openApp)
                .addAction(0, "🎮 游戏模式（暂停）", actionPi(ACTION_PAUSE))
                .addAction(0, "⏹ 停止监控", actionPi(ACTION_STOP))
        }
        return builder
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun actionPi(action: String): PendingIntent =
        PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, MonitorService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE
        )

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFY_ID, buildNotification())
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "行为监控",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Trace 行为采集服务状态与开关"
                setShowBadge(false)
            }
        )
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(poll)
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

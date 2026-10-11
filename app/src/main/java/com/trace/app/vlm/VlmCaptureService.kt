package com.trace.app.vlm

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
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.trace.app.MainActivity
import com.trace.app.MonitorService
import com.trace.app.R
import com.trace.app.ai.DailySummary
import com.trace.app.behavior.ActivityLabel
import com.trace.app.db.TraceDbHelper
import com.trace.app.util.AppNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 端侧 VLM 的「事件触发」前台服务（形态 C）。
 *
 * 两条触发通道：
 *  A. dwell 通道（[tick]，15 s 一轮询，走 UsageStats）：用户真在某个应用里停下来 → 截一帧分析。
 *  B. 内容通道（[sample]，2 s 一采样，走无障碍语义，只对 [TriggerMode.CONTENT] 的应用开）：
 *     抖音类信息流里「换了一条视频 / 点赞 / 收藏」各记一次；语义树读不到时退回 16×16 画面指纹。
 *
 * D. 关键帧存档：截一帧几十 ms，跑模型 15-20 s——所以先无条件把帧存进 frames 表，
 *    模型分析可以延后（[batchOnly] 模式下只存档；熄屏+充电时自动批量补分析）。
 *
 * 为什么要两套：UsageStats 只能看到「前台是哪个应用」，抖音里滑 100 条视频它一个事件都不发；
 * 而无障碍语义树能直接读出「未点赞/已点赞」「未选中/已选中」这种权威状态，零模型成本。
 */
class VlmCaptureService : Service() {

    companion object {
        private const val TAG = "TraceVlmSvc"

        const val ACTION_START = "com.trace.app.vlm.START"
        const val ACTION_STOP = "com.trace.app.vlm.STOP"
        const val ACTION_BATCH = "com.trace.app.vlm.BATCH"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        private const val CHANNEL_ID = "trace_vlm"
        private const val NOTIFY_ID = 1002

        /** 每日总结用单独的渠道与 id：它是一条「可以点进来看」的消息，不该混进常驻通知。 */
        private const val SUMMARY_CHANNEL_ID = "trace_digest"
        private const val SUMMARY_NOTIFY_ID = 1003

        /** UsageStats 轮询周期。 */
        private const val POLL_MS = 15_000L

        /** 内容采样周期：只对 CONTENT 模式的应用做重活（读语义树 / 抽指纹）。 */
        private const val SAMPLE_MS = 2_000L

        /** 点赞/收藏这类明确动作的冷却（不重复记，但也不用等 30 s）。 */
        private const val ACTION_COOLDOWN_MS = 10_000L

        /**
         * 「在干什么」：同一页面最多每 5 min 补一条心跳。
         * 页面一换立刻记；页面没换的话，5 min 一条也足够看出「他一直在刷抖音」。
         */
        private const val WHAT_HEARTBEAT_MS = 300_000L

        /** 查「这个应用现在停在哪个 Activity」的最短间隔（别每 2 s 打一次 SQL）。 */
        private const val WHAT_ACTIVITY_CHECK_MS = 4_000L

    /** 截屏失败后多久允许重试（不想白吃一整个冷却窗口）。 */
    private const val RETRY_AFTER_FAIL_MS = 10_000L

        /**
         * 回溯窗口。**必须够长**：UsageStats 只在「发生切换」时投递 ACTIVITY_RESUMED，
         * 用户在一个应用里待久了，最近 30 s 内可能一个事件都没有（早期版本用 30 s
         * 当窗口，结果在设置页干等 70 s 也触发不了）。最新一条 RESUMED 就是当前前台应用，
         * 所以放大到 10 min 只看「它是什么时候进来的」。
         */
        private const val LOOKBACK_MS = 600_000L
        private const val DWELL_MS = 8_000L
        private const val MIN_INTERVAL_MS = 60_000L
        private const val SAME_PKG_MS = 300_000L

        @Volatile var running = false
            private set

        @Volatile var autoCapture = true

        /** D：只存档、不立刻分析（模型留给熄屏+充电时批量跑）。 */
        @Volatile var batchOnly = false

        @Volatile var lastMessage = "未启动"

        @Volatile var captureCount = 0
            private set

        /** 无障碍语义记下的内容事件条数（点赞/收藏/换视频）。 */
        @Volatile var contentCount = 0
            private set

        /** UI 展示：最近一次读到的内容语义摘要。 */
        @Volatile var lastProbeSummary = ""
            private set

        /** 诊断：最近一次探针里与点赞/收藏有关的 desc（定位「读不到状态」用）。 */
        @Volatile var lastProbeDescs = ""
            private set

        /** UI 展示：最近一条「应用名 · 在干什么」。 */
        @Volatile var lastWhatMessage = ""
            private set

        /** 「在干什么」累计写入条数。 */
        @Volatile var whatCount = 0
            private set
    }

    private lateinit var db: TraceDbHelper
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var lastPkg: String? = null
    private var lastCaptureAt = 0L

    /** 内容通道的上一帧语义快照。 */
    private var lastProbe: ScreenProbe? = null
    private var lastProbePkg: String? = null

    /** 视觉兜底：语义树读不到时，用 16×16 指纹判断画面是否换了。 */
    private val watcher = FrameWatcher(mask = FrameWatcher.edgeMask())

    /** 模型是单槽的：同一时刻只允许一个分析在跑。 */
    private val analyzing = AtomicBoolean(false)

    /** 每日总结：同时只跑一份；[summaryAttemptDay] 记住"今天自动试过"，失败会清空重来。 */
    private val summaryRunning = AtomicBoolean(false)
    @Volatile private var summaryAttemptDay = ""

    /** 诊断用：上一次打探针日志的时刻 / 上一次读到的点赞状态。 */
    private var lastProbeLogAt = 0L
    private var lastLikeState: Int? = null

    /** 「在干什么」采样的状态：上一次记的是哪个应用、哪个页面、什么时候。 */
    private var whatPkg: String? = null
    private var whatActivity: String = ""
    private var whatWriteAt = 0L
    private var whatQueryAt = 0L

    private val poll = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (t: Throwable) {
                Log.e(TAG, "poll tick failed", t)
            }
            handler?.postDelayed(this, POLL_MS)
        }
    }

    private val sampler = object : Runnable {
        override fun run() {
            try {
                sample()
            } catch (t: Throwable) {
                Log.e(TAG, "sample failed", t)
            }
            handler?.postDelayed(this, SAMPLE_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = TraceDbHelper(this)
        val ht = HandlerThread("trace-vlm").apply { start() }
        thread = ht
        handler = Handler(ht.looper)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                startAsForeground(useProjection = data != null)
                if (data != null && !ScreenCapture.running) {
                    val ok = try {
                        ScreenCapture.start(this, resultCode, data)
                    } catch (t: Throwable) {
                        Log.e(TAG, "ScreenCapture.start crashed", t)
                        false
                    }
                    lastMessage = if (ok) "投屏已就绪 ${ScreenCapture.sizeText}，等待应用切换" else "投屏启动失败"
                    Log.i(TAG, lastMessage)
                } else if (!ScreenCapture.running && A11yCapture.ready) {
                    lastMessage = "无障碍截屏已就绪（整屏），等待应用切换"
                    Log.i(TAG, lastMessage)
                }
                running = true
                // 播种计时：投屏刚就绪时前台还是系统授权界面，别立刻截一帧。
                lastCaptureAt = System.currentTimeMillis()
                handler?.removeCallbacks(poll)
                handler?.post(poll)
                handler?.removeCallbacks(sampler)
                handler?.postDelayed(sampler, SAMPLE_MS)
                notifyStatus()
            }
            ACTION_BATCH -> {
                // 用户手动点「批量分析」：把攒下的关键帧按顺序喂给模型。
                Log.i(TAG, "ACTION_BATCH 收到 pending=${db.pendingFrameCount()} running=$running")
                startAsForeground(useProjection = ScreenCapture.running)
                if (lastCaptureAt == 0L) lastCaptureAt = System.currentTimeMillis()
                runBatch(limit = 10)
            }
            else -> {
                // 系统重启服务时没有 extras（START_STICKY 回来）。服务还在 = 用户上次是开着的，
                // 所以别停在「等待投屏授权」装死：无障碍在线就直接自愈恢复采集循环。
                startAsForeground(useProjection = false)
                if (A11yCapture.ready) {
                    lastMessage = "无障碍截屏已就绪（整屏），等待应用切换"
                    running = true
                    lastCaptureAt = System.currentTimeMillis()
                    handler?.removeCallbacks(poll)
                    handler?.post(poll)
                    handler?.removeCallbacks(sampler)
                    handler?.postDelayed(sampler, SAMPLE_MS)
                } else {
                    lastMessage = "等待投屏授权"
                }
                Log.i(TAG, "服务被系统重启：${lastMessage}")
                notifyStatus()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler?.removeCallbacks(poll)
        handler?.removeCallbacks(sampler)
        running = false
        ScreenCapture.stop()
        scope.cancel()
        thread?.quitSafely()
        thread = null
        handler = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------- A. dwell 通道（UsageStats）

    /** 上次尝试「自愈无障碍」的时间戳（60 s 限一次，别反复写系统设置）。 */
    private var lastHealAttempt = 0L

    private fun tick() {
        if (!autoCapture || !running) return
        // 采集源二选一：无障碍（整屏、免授权弹窗）优先，其次才是投屏会话。
        if (!A11yCapture.ready && !ScreenCapture.running) {
            // OriginOS 会在后台把第三方无障碍摘掉（实测闲置约两小时设置就空了），
            // 用户感觉「开了又自动关」。这里兜底自愈：拿到 WRITE_SECURE_SETTINGS 就能写回去，
            // 写进去系统会重新绑定服务，下一轮 tick 就恢复正常采集。
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastHealAttempt > 60_000L) {
                lastHealAttempt = nowMs
                if (A11yCapture.ensureEnabled(this)) {
                    Log.i(TAG, "无障碍被系统摘掉，已自愈写回，等系统重新绑定")
                    return
                }
            }
            Log.d(TAG, "tick: 没有可用的采集源（无障碍未连上且无投屏会话）")
            return
        }

        // 熄屏时截到的是黑帧，白烧几十秒 CPU——直接跳过。
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isInteractive) {
            Log.d(TAG, "tick: 屏幕未点亮，跳过")
            return
        }

        val now = System.currentTimeMillis()
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val events = usm.queryEvents(now - LOOKBACK_MS, now) ?: return
        val ev = UsageEvents.Event()

        var pkg: String? = null
        var resumedAt = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            val p = ev.packageName ?: continue
            if (isIgnored(p)) continue
            if (ev.timeStamp >= resumedAt) {
                resumedAt = ev.timeStamp
                pkg = p
            }
        }

        val target = pkg ?: run {
            Log.d(TAG, "tick: 最近 ${LOOKBACK_MS / 1000}s 内没有可用的前台应用事件")
            return
        }

        // UsageStats 只说「最后一个 RESUMED 的是谁」（Trace 自己会被 isIgnored 滤掉，
        // 所以用户切回 Trace 后 target 仍停在上一个应用）。截屏截的是眼前这一屏——
        // 与其在采集那一刻才发现对不上（白跑一次模型），不如先跟现实对齐：
        // 眼前不是 target 就把 lastPkg 认到眼前的应用，等它真的回来时按 pkgChanged 正常触发。
        val visible = A11yCapture.currentPkg()
        if (visible != null && visible != target) {
            Log.d(TAG, "tick: 眼前是 $visible，不是 $target，只对齐不采集")
            lastPkg = visible
            return
        }

        val pkgChanged = target != lastPkg
        val sinceCapture = now - lastCaptureAt
        val dwell = now - resumedAt

        if (dwell < DWELL_MS) {
            Log.d(TAG, "tick: $target 停留 ${dwell}ms，未到 $DWELL_MS")
            return
        }
        if (sinceCapture < MIN_INTERVAL_MS) {
            Log.d(TAG, "tick: $target 距上次截屏 ${sinceCapture}ms，未到 $MIN_INTERVAL_MS")
            return
        }
        if (!pkgChanged && sinceCapture < SAME_PKG_MS) {
            Log.d(TAG, "tick: $target 同一应用内 ${sinceCapture}ms，未到 $SAME_PKG_MS")
            return
        }

        lastPkg = target
        lastCaptureAt = now
        Log.i(TAG, "trigger pkg=$target changed=$pkgChanged sinceLast=${sinceCapture}ms")
        captureAndAnalyze(target, "DWELL", MIN_INTERVAL_MS)
    }

    // ------------------------------------------- B. 内容通道（无障碍语义 / 画面指纹）

    private fun sample() {
        if (!autoCapture || !running) return
        if (!A11yCapture.ready) return

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager

        // 空闲批量：熄屏 + 充电 = 可以放心烧 CPU，把攒下的关键帧补分析掉。
        if (pm != null && !pm.isInteractive) {
            val pending = db.pendingFrameCount()
            if (pending > 0 && isCharging()) {
                Log.i(TAG, "idle batch: 熄屏+充电，待分析 $pending 帧")
                runBatch(limit = 3)
            }
            // 帧补完了再写总结（同一个模型，先到先得，别两头抢）。
            if (db.pendingFrameCount() == 0) maybeDailySummary()
            return
        }

        val pkg = A11yCapture.currentPkg() ?: return
        if (isIgnored(pkg)) return
        val profile = AppProfiles.of(pkg)

        // 「在干什么」：全应用都记。**放在 NEVER 判断之前**——支付宝/云闪付这类
        // 应用不截屏、不读内容（allowProbe=false），但「在支付宝 · 付款」这种记录
        // 恰恰是用户想看的账目上下文。
        try {
            sampleWhat(pkg, allowProbe = profile.mode != TriggerMode.NEVER)
        } catch (t: Throwable) {
            Log.w(TAG, "sampleWhat failed", t)
        }

        if (profile.mode == TriggerMode.NEVER) return

        val now = System.currentTimeMillis()
        var contentChanged = false
        var action: String? = null
        var probeTitle = ""
        var probeAuthor = ""
        var eventRecorded = false

        if (profile.mode == TriggerMode.CONTENT || profile.mode == TriggerMode.BOTH) {
            val p = A11yCapture.readProbe()
            if (p != null) {
                probeTitle = p.title
                probeAuthor = p.author
                lastProbeSummary = p.summary()
                // 诊断：点赞/收藏在无障碍树里长什么样（每 5 s 或点赞状态一变就打一行）。
                lastProbeDescs = p.descs
                    .filter { it.contains("赞") || it.contains("喜欢") || it.contains("收藏") || it.contains("选中") }
                    .joinToString(" ¦ ").take(300)
                if (now - lastProbeLogAt > 5_000L || p.likedCount != lastLikeState) {
                    lastProbeLogAt = now
                    lastLikeState = p.likedCount
                    Log.i(TAG, "probe n=${p.nodes}${if (p.truncated) "+" else ""} thin=${p.isThin} " +
                        "like=${p.liked}/${p.likedCount} save=${p.saved}/${p.savedCount} title=${p.title.take(30)}")
                    Log.i(TAG, "  probe 赞/藏: $lastProbeDescs")
                }
            } else {
                Log.d(TAG, "probe null（读不到活动窗口）")
            }

            if (p != null && p.pkg == pkg && !p.isThin) {
                // 语义树是「富」的：点赞/收藏是权威状态，标题/计数免费拿。
                val prev = lastProbe
                val prevPkg = lastProbePkg

                if (prev != null && prevPkg == pkg && !prev.isThin) {
                    // 1) 换了一条内容：标题/作者变了（计数会被别人点赞带着跳，不算键）
                    val key = p.contentKey()
                    val prevKey = prev.contentKey()
                    val sameContent = key.isNotBlank() && key == prevKey
                    if (!sameContent) {
                        db.addContentEvent(now, pkg, "VIDEO_CHANGED", p.title, p.author, "")
                        contentCount += 1
                        contentChanged = true
                        eventRecorded = true
                    }
                    // 2) 点赞：抖音一屏会挂好几支视频的按钮，所以看「有几条是已赞态」。
                    //    条数变多 ⇒ 一定是有人按了赞（滑走那条已赞视频只会让条数变少），
                    //    即使内容同时换了也认——用户常「滑一下顺手点个赞」。
                    //    条数变少 ⇒ 可能是「取消」，也可能是「已赞的那条滑走了」，
                    //    所以要求同内容、或有计数证据 (v,未赞) → (v+1,已赞)。
                    val pl = prev.likedCount
                    val nl = p.likedCount
                    if (pl != nl && (nl > pl || sameContent || flipped(prev.likePairs, p.likePairs, false))) {
                        val e = if (nl > pl) "LIKE" else "UNLIKE"
                        Log.i(TAG, "内容事件 $e sameContent=$sameContent $pl→$nl pairs=${p.likePairs}")
                        db.addContentEvent(now, pkg, e, p.title, p.author, "$pl→$nl")
                        contentCount += 1
                        action = e
                    }
                    // 3) 收藏：认「未选中/已选中」，规则同点赞。
                    val ps = prev.savedCount
                    val ns = p.savedCount
                    if (ps != ns && (ns > ps || sameContent || flipped(prev.savePairs, p.savePairs, false))) {
                        val e = if (ns > ps) "SAVE" else "UNSAVE"
                        Log.i(TAG, "内容事件 $e sameContent=$sameContent $ps→$ns pairs=${p.savePairs}")
                        db.addContentEvent(now, pkg, e, p.title, p.author, "$ps→$ns")
                        contentCount += 1
                        action = e
                    }
                } else {
                    // 刚进入这个应用（或上一次读到的是薄树）：视作内容已变
                    contentChanged = true
                }
                lastProbe = p
                lastProbePkg = pkg
            } else {
                // 语义树读不到、或太薄（节点超过遍历上限时也会变薄）
                // → 视觉兜底：16×16 灰度指纹，只在画面真换了才触发。
                // 模型在跑时跳过指纹：CPU 已被占满，系统截屏回调会超时，白等。
                if (!analyzing.get()) {
                    val fp = A11yCapture.captureFingerprint()
                    if (fp != null && watcher.feed(fp)) contentChanged = true
                }
            }
        }

        // 明确动作（点赞/收藏）优先，冷却 10 s；内容切换受各应用自己的 minIntervalMs 节制。
        if (action != null) {
            if (now - lastCaptureAt >= ACTION_COOLDOWN_MS) {
                lastPkg = pkg
                lastCaptureAt = now
                Log.i(TAG, "trigger(动作) pkg=$pkg event=$action")
                captureAndAnalyze(pkg, action, ACTION_COOLDOWN_MS)
            }
            return
        }
        if (contentChanged && now - lastCaptureAt >= profile.minIntervalMs) {
            lastPkg = pkg
            lastCaptureAt = now
            if (!eventRecorded && AppProfiles.isStream(pkg)) {
                // 指纹驱动的换内容：记一笔，这样「每个视频一条记录」在库里也成立
                db.addContentEvent(now, pkg, "VIDEO_CHANGED", probeTitle, probeAuthor, "fp")
                contentCount += 1
            }
            Log.i(TAG, "trigger(内容) pkg=$pkg diff=${"%.1f".format(watcher.lastDiff)}")
            captureAndAnalyze(pkg, "CONTENT", profile.minIntervalMs)
        }
    }

    /**
     * 「在干什么」采样。和内容通道共用 2 s 循环，但目的完全不同：
     * 内容通道只服务 CONTENT 类应用（为了截关键帧），这里服务**所有**应用，
     * 只为了把行为流从「包名 · Activity [RESUMED]」变成「抖音 · 在刷视频」。
     *
     * 成本控制（这是它能挂在高频循环里的原因）：
     *  - 不截屏、不跑模型；
     *  - 「当前页面」直接查 usage_events 里 MonitorService 已经写好的 Activity 类名（4 s 一次）；
     *  - 页面没换、又没到 5 min 心跳时**直接返回**（绝大多数回合走这条）；
     *  - 需要明细时优先白拿内容通道刚读过的语义快照，否则读一次轻量版（500 节点 / 400ms）。
     *
     * @param allowProbe false 时只用 Activity 类名推断（支付类应用不读界面内容）。
     */
    private fun sampleWhat(pkg: String, allowProbe: Boolean) {
        val now = System.currentTimeMillis()

        // 1) 这个应用现在停在哪个页面。同名包没变就不必每 2 s 都查库。
        var activity = whatActivity
        if (pkg != whatPkg || now - whatQueryAt >= WHAT_ACTIVITY_CHECK_MS) {
            whatQueryAt = now
            activity = db.lastActivityFor(pkg).orEmpty()
        }
        val pageChanged = pkg != whatPkg || activity != whatActivity
        if (!pageChanged && now - whatWriteAt < WHAT_HEARTBEAT_MS) return

        // 2) 明细：内容通道 5 s 内读过就白拿，否则读一次轻量版。
        val cached = lastProbe?.takeIf { lastProbePkg == pkg && now - it.ts <= 5_000L }
        val probe = cached
            ?: if (allowProbe) A11yCapture.readProbeLite()?.takeIf { it.pkg == pkg } else null

        val what = ActivityLabel.describe(pkg, activity, probe)
        val detail = ActivityLabel.detail(probe)

        whatPkg = pkg
        whatActivity = activity
        whatWriteAt = now
        db.addActivityLog(now, pkg, activity, what, detail, if (probe != null) "a11y" else "activity")
        whatCount += 1

        val line = AppNames.of(this, pkg) + " · " + what + (if (detail.isNotEmpty()) " — $detail" else "")
        lastWhatMessage = line
        Log.i(TAG, "what: $line")
    }

    private fun isIgnored(pkg: String): Boolean =
        pkg == packageName ||
            pkg == "com.android.systemui" ||   // 授权弹窗/下拉栏，不是用户真正在用的应用
            pkg == "com.android.packageinstaller" ||   // 安装器界面（adb install 会把它顶到前台）
            pkg == "com.google.android.packageinstaller" ||
            pkg == "com.android.permissioncontroller" ||   // 权限弹窗
            MonitorService.isGame(pkg)

    private fun isCharging(): Boolean {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return false
        return bm.isCharging
    }

    /**
     * 计数证据：画面换了，但某个按钮确实被人按过。
     *
     * 「未点赞，喜欢12」→「已点赞，喜欢13」留下 (12, false) → (13, true)；
     * 而「已赞的视频滑走」只会让已赞条数 -1，不会凭空出现 v-1 的未赞态，所以不会误判。
     * 万级计数（喜欢1.9万）不随一次点赞变化，故同时接受同一个 v。
     */
    private fun flipped(
        before: List<Pair<Long, Boolean>>,
        after: List<Pair<Long, Boolean>>,
        up: Boolean,
    ): Boolean {
        val was = before.filter { it.second != up }.mapTo(HashSet()) { it.first }
        val delta = if (up) -1L else 1L
        return after.any { it.second == up && (was.contains(it.first) || was.contains(it.first + delta)) }
    }

    // ---------------------------------------------------------------- 截屏 / 存档 / 分析

    /**
     * 把刚刚送去分析的那一帧落到外部文件目录，方便 `adb pull` 出来核对，
     * 也当「模型到底看到了什么」的证据留痕。
     * 路径：/sdcard/Android/data/com.trace.app/files/last-capture.jpg
     */
    private fun dumpLastFrame(jpeg: ByteArray) {
        try {
            val f = File(getExternalFilesDir(null), "last-capture.jpg")
            f.writeBytes(jpeg)
            Log.i(TAG, "frame dumped ${jpeg.size}B → ${f.absolutePath}")
        } catch (t: Throwable) {
            Log.w(TAG, "dump frame failed", t)
        }
    }

    /** 关键帧存档：frames 表 + files/frames/<ts>_<trigger>.jpg。返回行 id，失败 -1。 */
    private fun archiveFrame(jpeg: ByteArray, pkg: String, trigger: String): Long {
        return try {
            val dir = File(getExternalFilesDir(null), "frames").apply { mkdirs() }
            val ts = System.currentTimeMillis()
            val safe = trigger.replace(Regex("[^A-Za-z0-9_]"), "_")
            val f = File(dir, "${ts}_$safe.jpg")
            f.writeBytes(jpeg)
            val id = db.addFrame(ts, pkg, trigger, f.absolutePath)
            Log.i(TAG, "frame archived #$id ${jpeg.size}B → ${f.name}")
            id
        } catch (t: Throwable) {
            Log.w(TAG, "archive frame failed", t)
            -1L
        }
    }

    private fun captureAndAnalyze(pkg: String, trigger: String, cooldownMs: Long) {
        val startedAt = System.currentTimeMillis()
        scope.launch {
            // 截屏截的是「眼前这一屏」：如果此刻可见的应用不是目标 pkg，这帧必然张冠李戴。
            // 实测踩过：UsageStats 最近 resumed 还是抖音，但人已经切回 Trace 看状态，
            // 于是把 Trace 自己的界面当成抖音 DWELL 洞察入了库（frame 11 / insight 22）。
            // 宁可跳过，也不要脏数据。
            val visible = A11yCapture.currentPkg()
            if (visible != null && visible != pkg) {
                lastMessage = "跳过（眼前是 $visible，目标 $pkg）"
                Log.i(TAG, "skip capture: visible=$visible target=$pkg trigger=$trigger")
                notifyStatus()
                return@launch
            }
            lastMessage = "分析中… $pkg ($trigger)"
            // 无障碍截屏优先：整屏、不用授权弹窗；不可用时退回投屏帧。
            val jpeg = A11yCapture.captureJpeg() ?: ScreenCapture.captureJpeg()
            if (jpeg == null) {
                // 截屏失败（实测：模型占满 CPU 时系统截屏回调会超时）。
                // 别白吃一整个冷却窗口，10 s 后允许重试。
                lastCaptureAt = startedAt - cooldownMs + RETRY_AFTER_FAIL_MS
                lastMessage = "截屏失败：$pkg (code=${A11yCapture.lastShotError})"
                notifyStatus()
                return@launch
            }
            dumpLastFrame(jpeg)
            val frameId = archiveFrame(jpeg, pkg, trigger)

            if (batchOnly) {
                lastMessage = "已存档关键帧（$trigger）：$pkg\n待分析 ${db.pendingFrameCount()} 帧"
                notifyStatus()
                return@launch
            }
            if (!analyzing.compareAndSet(false, true)) {
                // 模型在忙：这一帧照样存下了，等批量分析时补。
                lastMessage = "模型忙，已存档关键帧（$trigger）：$pkg"
                notifyStatus()
                return@launch
            }
            try {
                val slice = AdaptiveSlice.forEvent(db.lastScreenInsightFor(pkg)?.answer)
                val r = VlmEngine.analyze(this@VlmCaptureService, jpeg, slice)

                if (r.ok) {
                    val id = db.addScreenInsight(pkg, "", r.slice, r.prefillMs, r.genMs, cleanAnswer(r.answer))
                    if (frameId > 0) db.markFrameAnalyzed(frameId, id)
                    captureCount += 1
                    lastMessage = "已分析 $pkg ($trigger, slice=${r.slice} " +
                        "预填充=${r.prefillMs}ms 生成=${r.genMs}ms)\n${cleanAnswer(r.answer).take(120)}"
                    Log.i(TAG, "insight pkg=$pkg trigger=$trigger slice=${r.slice} " +
                        "prefill=${r.prefillMs}ms gen=${r.genMs}ms | ${cleanAnswer(r.answer).take(120)}")
                } else {
                    lastMessage = "分析失败 $pkg：${r.error}"
                }
            } finally {
                analyzing.set(false)
            }
            notifyStatus()
        }
    }

    /** 批量补分析：把 frames 表里 analyzed=0 的老帧按时间顺序喂给模型。 */
    /** MiniCPM 的答案会带 <think>…</think> 推理段：入库/展示前统一剥掉。 */
    private fun cleanAnswer(s: String): String {
        var t = s
        val a = t.indexOf("<think>")
        if (a >= 0) {
            val b = t.indexOf("</think>", a)
            t = if (b >= 0) t.removeRange(a, b + "</think>".length) else t.substring(0, a)
        }
        return t.trim()
    }

    private fun runBatch(limit: Int = 10) {
        Log.i(TAG, "runBatch enter limit=$limit analyzing=${analyzing.get()}")
        if (!analyzing.compareAndSet(false, true)) {
            Log.d(TAG, "runBatch: 模型忙，跳过")
            return
        }
        scope.launch {
            try {
                val pending = db.pendingFrames(limit)
                if (pending.isEmpty()) {
                    lastMessage = "没有待分析的关键帧"
                    notifyStatus()
                    return@launch
                }
                var done = 0
                for (f in pending) {
                    val path = f.jpegPath
                    val file = if (path == null) null else File(path)
                    if (file == null || !file.isFile) {
                        db.markFrameAnalyzed(f.id, -1L)
                        continue
                    }
                    val bytes = try {
                        file.readBytes()
                    } catch (t: Throwable) {
                        Log.w(TAG, "read frame failed: $path", t)
                        null
                    }
                    if (bytes == null) {
                        db.markFrameAnalyzed(f.id, -1L)
                        continue
                    }
                    val slice = AdaptiveSlice.forEvent(db.lastScreenInsightFor(f.pkg)?.answer)
                    val r = VlmEngine.analyze(this@VlmCaptureService, bytes, slice)
                    if (!r.ok) {
                        lastMessage = "批量分析中断于 ${f.pkg}：${r.error}"
                        notifyStatus()
                        break
                    }
                    val id = db.addScreenInsight(f.pkg, "", r.slice, r.prefillMs, r.genMs, cleanAnswer(r.answer))
                    db.markFrameAnalyzed(f.id, id)
                    captureCount += 1
                    done += 1
                    lastMessage = "批量分析 $done/${pending.size}：${f.pkg} (${f.trigger})\n${cleanAnswer(r.answer).take(80)}"
                    notifyStatus()
                }
                lastMessage = "批量分析完成：$done/${pending.size} 帧"
                Log.i(TAG, "batch done $done/${pending.size}")
            } catch (t: Throwable) {
                Log.e(TAG, "batch failed", t)
                lastMessage = "批量分析异常：${t.message}"
            } finally {
                analyzing.set(false)
                notifyStatus()
            }
        }
    }

    // ------------------------------------------------------------ 通知/前台

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "端侧屏幕理解", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Trace · 端侧屏幕理解")
            .setContentText(lastMessage.lineSequence().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(lastMessage))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    /**
     * 前台服务类型必须和「实际在用的采集方式」一致，否则 Android 14+ 直接抛
     * SecurityException 把整个进程干掉：
     *   Starting FGS with type mediaProjection … requires permissions:
     *   allOf=true [FOREGROUND_SERVICE_MEDIA_PROJECTION]
     *   any of [CAPTURE_VIDEO_OUTPUT, android:project_media]
     * 走无障碍截屏时用户从没授权过投屏，`android:project_media` 这个 appop 不存在，
     * 于是必须声明成 dataSync；只有真的拿到投屏 consent（intent 里带 data）时
     * 才能声明 mediaProjection。清单里两种类型都要登记。
     */
    private fun startAsForeground(useProjection: Boolean) {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (useProjection) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(NOTIFY_ID, n, type)
        } else {
            startForeground(NOTIFY_ID, n)
        }
    }

    private fun notifyStatus() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFY_ID, buildNotification())
        } catch (t: Throwable) {
            Log.w(TAG, "notify failed", t)
        }
    }

    // ------------------------------------------------------------ 每日总结

    /**
     * 熄屏时自动生成当天的「今日总结」并留档。
     *
     * 为什么必须熄屏：写一份总结要独占端侧模型 30-60 s；用户正在看屏幕时抢模型，
     * 会把屏幕理解挤慢，手机也跟着发烫。所以规矩是：
     *   - 熄屏（且关键帧都补完了，见 [sample]）；
     *   - 今天还没有总结（想重写请点首页的按钮）；
     *   - 充电时 18:00 之后就能跑；没充电要等到 23:00，且一天只自动试一次
     *     （不然用户半夜频繁熄屏会反复空跑）。
     * 失败（模型忙 / 没数据）会把「今天试过」标记清掉，下次熄屏再试。
     */
    private fun maybeDailySummary() {
        if (summaryRunning.get()) return
        val day = DailySummary.dayKey()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val charging = isCharging()
        val due = if (charging) hour >= 18 else hour >= 23
        if (!due) return
        if (db.latestDigest(day) != null) return
        if (!charging && summaryAttemptDay == day) return

        val (start, end) = DailySummary.dayRange(day)
        if (db.totalUsageMs(start, end) <= 0 && db.recordCountInRange(start, end) == 0) {
            Log.d(TAG, "daily summary: $day 还没有数据，跳过")
            return
        }

        summaryAttemptDay = day
        summaryRunning.set(true)
        Log.i(TAG, "daily summary: 开始生成 $day（charging=$charging）")
        scope.launch {
            try {
                val r = DailySummary.generate(this@VlmCaptureService, db, day)
                if (r.ok) {
                    Log.i(TAG, "daily summary: ok engine=${r.engine} 字数=${r.text.length}")
                    notifySummary(r.text)
                } else {
                    Log.w(TAG, "daily summary: 失败 ${r.error}")
                    summaryAttemptDay = ""
                }
            } catch (t: Throwable) {
                Log.e(TAG, "daily summary crashed", t)
                summaryAttemptDay = ""
            } finally {
                summaryRunning.set(false)
            }
        }
    }

    /** 总结写好后的通知：点一下直接进首页看完整版。 */
    private fun notifySummary(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(SUMMARY_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        SUMMARY_CHANNEL_ID, "Trace 每日总结", NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
            }
            val pi = PendingIntent.getActivity(
                this, 1, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(this, SUMMARY_CHANNEL_ID)
                .setContentTitle("今天的 Trace 总结写好了")
                .setContentText(text.lineSequence().firstOrNull() ?: "")
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(SUMMARY_NOTIFY_ID, n)
        } catch (t: Throwable) {
            Log.w(TAG, "summary notify failed", t)
        }
    }
}
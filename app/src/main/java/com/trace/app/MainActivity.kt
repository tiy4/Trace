package com.trace.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.trace.app.ai.DailySummary
import com.trace.app.db.TraceDbHelper
import com.trace.app.insight.InsightEngine
import com.trace.app.ui.BottomNav
import com.trace.app.util.AppNames
import com.trace.app.vlm.A11yCapture
import com.trace.app.vlm.AdaptiveSlice
import com.trace.app.vlm.ScreenCapture
import com.trace.app.vlm.VlmCaptureService
import com.trace.app.vlm.VlmEngine
import com.trace.app.vlm.VlmPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private companion object {
        /** 「上次 VLM 采集是开着的」——进程会死，这个标记不会。 */
        const val KEY_VLM_ON = "vlm_on"
        const val PREFS = "trace_prefs"
    }

    private fun vlmPrefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)


    private lateinit var db: TraceDbHelper
    private lateinit var insight: InsightEngine

    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var suppressSwitch = false

    /** 投屏授权结果 → 带着 resultCode/data 启动端侧理解服务。 */
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val i = Intent(this, VlmCaptureService::class.java).apply {
                action = VlmCaptureService.ACTION_START
                putExtra(VlmCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(VlmCaptureService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(this, i)
        } else {
            setVlmSwitch(false)
        }
        refreshVlm()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = TraceDbHelper(this)
        insight = InsightEngine()

        // 底部导航（底部玻璃条）统一由 BottomNav 绑定；首页这一份只负责高亮与跳转。
        BottomNav.bind(this, BottomNav.HOME)
        handleNavIntent(intent)
        findViewById<Button>(R.id.btnMonitor).setOnClickListener { toggleMonitor() }

        findViewById<Switch>(R.id.swVlm).setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            vlmPrefs().edit().putBoolean(KEY_VLM_ON, checked).apply()
            if (checked) startVlmSession() else stopVlmSession()
        }
        findViewById<Button>(R.id.btnVlmNow).setOnClickListener { analyzeNow() }
        findViewById<Button>(R.id.btnVlmLog).setOnClickListener { showVlmLog() }
        findViewById<Button>(R.id.btnVlmA11y).setOnClickListener { openA11ySettings() }
        findViewById<Button>(R.id.btnVlmBatch).setOnClickListener { requestBatch() }
        findViewById<Button>(R.id.btnVlmArchive).setOnClickListener { toggleArchiveMode() }
        findViewById<Button>(R.id.btnVlmUnload).setOnClickListener {
            uiScope.launch {
                VlmEngine.unload()
                refreshVlm()
            }
        }

        findViewById<Button>(R.id.btnDigest).setOnClickListener { generateDigest() }
        findViewById<Button>(R.id.btnDigestHistory).setOnClickListener {
            startActivity(Intent(this, DigestHistoryActivity::class.java))
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        autoResumeVlm()
    }

    /**
     * 上次是开着的 → 重进 App 自动恢复采集。
     *
     * 为什么需要：`running` 是进程内静态量，进程一死（重装/被系统回收/重启手机）
     * 开关状态就归零，用户会看到「明明开着却显示未开启」，而 START_STICKY 把服务
     * 拉回来时又是没有 extras 的空 intent。与其让用户每次手动再点一次，不如把
     * 「上次开着」落到 SharedPreferences 上，进 App 就自己接上——这也符合
     * 本机无障碍「开一次长期有效」的定位。
     */
    private fun autoResumeVlm() {
        // OriginOS 会在后台把第三方无障碍摘掉（实测闲置约两小时后设置直接变空），
        // 用户看到的就是「我明明开了，一出来又自动关了」。进 App 先自愈一次：
        // 只要 adb 一次性给过 WRITE_SECURE_SETTINGS，就能自己写回去，不用再麻烦用户。
        val healed = A11yCapture.ensureEnabled(this)
        if (healed && !A11yCapture.ready) {
            findViewById<Switch>(R.id.swVlm).postDelayed({ refreshVlm() }, 1500)
        }
        if (VlmCaptureService.running) return
        if (!vlmPrefs().getBoolean(KEY_VLM_ON, false)) return
        ContextCompat.startForegroundService(
            this,
            Intent(this, VlmCaptureService::class.java).setAction(VlmCaptureService.ACTION_START)
        )
        // 无障碍服务可能比 Activity 晚连上，服务侧会等到 ready 再采，这里只负责把 UI 对上。
        findViewById<Switch>(R.id.swVlm).postDelayed({ refreshVlm() }, 800)
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    // ===== 行为监控开关 =====

    private fun toggleMonitor() {
        if (MonitorService.running) {
            stopService(Intent(this, MonitorService::class.java))
        } else {
            // Android 13+ 通知权限
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java))
        }
        // 等 300ms 让服务状态更新再刷新 UI
        findViewById<Button>(R.id.btnMonitor).postDelayed({ refresh() }, 300)
    }

    private fun refresh() {
        val records = db.allRecords()
        val total = records.sumOf { it.amount }
        val nightCount = records.count { insight.isNightImpulse(it) }
        val report = insight.generateReport(records)

        findViewById<TextView>(R.id.tvTotalCount).text = "共 ${records.size} 笔"
        findViewById<TextView>(R.id.tvTotalAmount).text = "¥ %.2f".format(total)
        findViewById<TextView>(R.id.tvNightCount).text = "深夜冲动 $nightCount 笔"
        findViewById<TextView>(R.id.tvReport).text = report
        refreshDigest()

        // 监控状态
        val running = MonitorService.running
        val btn = findViewById<Button>(R.id.btnMonitor)
        val tv = findViewById<TextView>(R.id.tvMonitorStatus)
        if (running) {
            btn.text = "⏹ 停止行为监控"
            val n = db.usageEventCount()
            tv.text = "✅ 监控运行中 · 已采集 $n 条行为事件\n通知栏可随时开游戏模式暂停"
        } else {
            btn.text = "▶ 开启行为监控"
            tv.text = "监控未开启 · 记录应用使用行为供 AI 分析"
        }

        refreshVlm()
    }

    // ===== 端侧屏幕理解（VLM）=====

    private fun setVlmSwitch(v: Boolean) {
        suppressSwitch = true
        findViewById<Switch>(R.id.swVlm).isChecked = v
        suppressSwitch = false
    }

    /**
     * 采集源选择：**无障碍可用就走无障碍**——整屏、免投屏授权弹窗、开一次长期有效；
     * 否则退回 MediaProjection（每次进程重启都要用户点授权，且本机弹窗默认
     * 只共享单个应用，必须手动切成「共享整个屏幕」）。
     */
    private fun startVlmSession() {
        if (!VlmPaths.ready(this)) {
            setVlmSwitch(false)
            AlertDialog.Builder(this)
                .setTitle("模型文件缺失")
                .setMessage(
                    VlmPaths.describe(this) +
                        "\n\n用 adb 推送模型到：\n" + VlmPaths.internalDir(this) +
                        "\n\n主模型 " + VlmPaths.GGUF_NAME + "\n视觉投影 " + VlmPaths.MMPROJ_NAME
                )
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        if (A11yCapture.ready) {
            // 无障碍在线：直接起服务，不需要任何授权弹窗。
            ContextCompat.startForegroundService(
                this,
                Intent(this, VlmCaptureService::class.java).setAction(VlmCaptureService.ACTION_START)
            )
            Toast.makeText(this, "无障碍截屏已启用，无需投屏授权", Toast.LENGTH_SHORT).show()
            findViewById<Button>(R.id.btnVlmNow).postDelayed({ refreshVlm() }, 500)
            return
        }

        if (A11yCapture.isEnabled(this)) {
            // 已在设置里开启，但服务进程还没连上（刚开/刚重启）：稍等一下再启动
            Toast.makeText(this, "无障碍服务正在连接，稍后再试一次开关", Toast.LENGTH_SHORT).show()
            setVlmSwitch(false)
            return
        }

        AlertDialog.Builder(this)
            .setTitle("选择截屏方式")
            .setMessage(
                "推荐先开「无障碍截屏」：整屏截图、开一次长期有效，" +
                    "重启手机后也不用重新授权。\n\n" +
                    "继续用投屏的话：本机授权弹窗默认是「共享一个应用」，" +
                    "必须手动切成「共享整个屏幕」；而且每次重启 Trace 都要重新授权一次。"
            )
            .setPositiveButton("去开无障碍") { _, _ -> openA11ySettings() }
            .setNegativeButton("用投屏授权") { _, _ ->
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(projectionIntent(mpm))
            }
            .setNeutralButton("取消") { _, _ -> setVlmSwitch(false) }
            .show()
    }

    /**
     * 发起投屏授权。
     *
     * Android 14（API 34）起应用可以主动声明「我要采集整个屏幕」：
     * `MediaProjectionConfig.createConfigForDefaultDisplay()`，系统弹窗就会
     * 以「共享整个屏幕」为默认、不再默认让用户挑单个应用（本机 v 默认选项是
     * 「共享一个应用」，用户每次都得手动切）。真正「替用户点同意」的 API 不存在，
     * 也不该有——授权必须由用户在本人在场的那次弹窗里完成。
     */
    private fun projectionIntent(mpm: MediaProjectionManager): Intent {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                return mpm.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            } catch (t: Throwable) {
                // 厂商改过实现就退回默认弹窗
            }
        }
        return mpm.createScreenCaptureIntent()
    }

    private fun openA11ySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "请在列表里找到「Trace 屏幕理解（截屏）」并打开", Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "打不开无障碍设置：${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopVlmSession() {
        startService(
            Intent(this, VlmCaptureService::class.java).setAction(VlmCaptureService.ACTION_STOP)
        )
        uiScope.launch { VlmEngine.unload() }
        refreshVlm()
    }

    private fun analyzeNow() {
        if (VlmEngine.isBusy()) {
            Toast.makeText(this, "模型正忙，稍候再试", Toast.LENGTH_SHORT).show()
            return
        }
        if (!A11yCapture.ready && !ScreenCapture.running) {
            AlertDialog.Builder(this)
                .setTitle("还没有截屏能力")
                .setMessage(
                    "两种方式二选一：\n\n" +
                        "① 开「无障碍截屏」（推荐，整屏、长期有效）\n" +
                        "② 打开上面的开关走投屏授权"
                )
                .setPositiveButton("知道了", null)
                .show()
            setVlmSwitch(false)
            return
        }
        val tv = findViewById<TextView>(R.id.tvVlmStatus)
        tv.text = "分析中…（首次会先加载模型，约 2 s；slice=3 视觉预填充约 11 s）"
        uiScope.launch {
            val jpeg = withContext(Dispatchers.IO) {
                A11yCapture.captureJpeg() ?: ScreenCapture.captureJpeg()
            }
            if (jpeg == null) {
                tv.text = "截屏失败：拿不到投屏帧"
                return@launch
            }
            val r = VlmEngine.analyze(this@MainActivity, jpeg, AdaptiveSlice.USER)
            tv.text = if (r.ok) {
                "slice=${r.slice} · 预填充 ${r.prefillMs} ms · 生成 ${r.genMs} ms\n\n${r.answer}"
            } else {
                "分析失败：${r.error}"
            }
        }
    }

    private fun showVlmLog() {
        val list = db.recentScreenInsights(10)
        val msg = if (list.isEmpty()) {
            "还没有端侧理解记录。"
        } else {
            list.joinToString("\n\n") { it ->
                val t = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(it.timestamp))
                // 以前这里直接打 `it.pkg`（com.ss.android.ugc.aweme），现在换成应用名。
                "$t · ${AppNames.of(this, it.pkg)} · slice=${it.slice} · ${it.prefillMs}ms+${it.genMs}ms\n${it.answer.take(120)}"
            }
        }
        AlertDialog.Builder(this)
            .setTitle("端侧理解记录（最近 10 条）")
            .setMessage(msg)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun refreshVlm() {
        val btnArchive = findViewById<Button>(R.id.btnVlmArchive)
        // 开关的真值来源是「服务在不在跑」。进程被杀后静态量归零，开关会画成关闭，
        // 而服务可能已被 autoResumeVlm / START_STICKY 自愈拉起 —— 那样就成了
        // 「服务明明在跑、开关却显示关着」，前后矛盾（实测踩过）。
        if (!suppressSwitch) setVlmSwitch(VlmCaptureService.running)

        // 文案要短：按钮现在是并排的两个 44dp 小按钮，长句会折成两行。
        // 含义放在卡片底部那条 caption 里（见 activity_main.xml）。
        btnArchive.text = if (VlmCaptureService.batchOnly) "仅存档：开" else "仅存档：关"

        val tv = findViewById<TextView>(R.id.tvVlmStatus)
        tv.text = buildString {
            append(if (VlmCaptureService.running) "✅ 事件触发运行中" else "⚪️ 未开启")
            append(" · 累计分析 ${db.screenInsightCount()} 次")
            append("\n关键帧：存档 ${db.frameCount()} · 待分析 ${db.pendingFrameCount()}")
            append("\n内容事件：${db.contentEventCount()} 次")
            append("（赞 ${db.contentEventCountOf("LIKE")} · 藏 ${db.contentEventCountOf("SAVE")}")
            append(" · 换视频 ${db.contentEventCountOf("VIDEO_CHANGED")}）")
            append("\n模型：")
            append(if (VlmEngine.isModelLoaded()) "已加载（占用约 2.4 GB 内存）" else "未加载（按需加载）")
            append("\n截屏：")
            append(
                when {
                    A11yCapture.ready -> "无障碍（整屏 · 免授权弹窗）"
                    A11yCapture.isEnabled(this@MainActivity) -> "无障碍已开启（服务连接中…）"
                    ScreenCapture.running -> "投屏 ${ScreenCapture.sizeText}"
                    else -> "未就绪"
                }
            )
            if (!A11yCapture.isEnabled(this@MainActivity)) {
                // 无障碍一旦被系统摘掉（重装 / am force-stop 都会），CONTENT 通道就废了：
                // 探针与画面指纹都建立在无障碍之上，投屏只能按"停留"触发。
                // 这里把话说透，别让用户看到"投屏已就绪"就以为一切正常。
                append("\n⚠️ 无障碍截屏未开启：换内容 / 点赞 / 收藏检测不可用，")
                append("只剩按停留触发。点上面「开启无障碍截屏」重开即可恢复")
                append("（重装或强制停止后系统会自动摘掉，也可跑 scripts/enable-a11y.ps1）。")
            }
            if (VlmCaptureService.running) {
                append("\n打开应用停留 8 秒后自动截屏分析\n")
                append(VlmCaptureService.lastMessage)
            }
            if (VlmCaptureService.lastProbeSummary.isNotEmpty()) {
                append("\n语义：")
                append(VlmCaptureService.lastProbeSummary)
            }
            if (VlmCaptureService.lastWhatMessage.isNotEmpty()) {
                // 「应用名 · 在干什么」，行为流里那套的实时一行。
                append("\n最近：")
                append(VlmCaptureService.lastWhatMessage)
                append("（应用内行为 ${db.activityLogCount()} 条）")
            }
            if (!VlmPaths.ready(this@MainActivity)) {
                append("\n⚠️ 模型文件缺失·外部目录=")
                append(VlmPaths.externalDir(this@MainActivity).absolutePath)
                append("\n可读=")
                append(VlmPaths.externalDir(this@MainActivity).canRead())
            }
        }
    }

    /** D：手动触发批量补分析——把 frames 表里攒下的关键帧按时间顺序喂给模型。 */
    private fun requestBatch() {
        val intent = Intent(this, VlmCaptureService::class.java)
            .setAction(VlmCaptureService.ACTION_BATCH)
        android.util.Log.i("TraceMain", "requestBatch → startForegroundService(ACTION_BATCH)")
        ContextCompat.startForegroundService(this, intent)
        findViewById<Button>(R.id.btnVlmBatch).postDelayed({ refreshVlm() }, 1500)
    }

    /** D：仅存档 / 触发即分析 两种模式的切换。 */
    private fun toggleArchiveMode() {
        VlmCaptureService.batchOnly = !VlmCaptureService.batchOnly
        refreshVlm()
    }

    // ===== 今日总结 =====

    private val digestTimeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)

    /** 只读库、不触发模型：把最近一份总结画到卡片上。 */
    private fun refreshDigest() {
        val d = db.latestDigest(DailySummary.dayKey())
        val tv = findViewById<TextView>(R.id.tvDigest)
        val meta = findViewById<TextView>(R.id.tvDigestMeta)
        val btn = findViewById<Button>(R.id.btnDigest)
        if (d == null) {
            tv.text = "今天还没有总结。"
            meta.text = if (db.digestCount() > 0) {
                "熄屏充电时会自动写一份（端侧模型，约 1 分钟）· 历史留档 ${db.digestCount()} 份"
            } else {
                "熄屏充电时会自动写一份（端侧模型，约 1 分钟）"
            }
            btn.text = "写今天总结"
        } else {
            tv.text = d.text
            val where = if (d.engine == "cloud") "云端" else "端侧"
            meta.text = "$where · ${d.model} · ${digestTimeFmt.format(java.util.Date(d.createdAt))} · " +
                "留档共 ${db.digestCount()} 份（点「历史总结」回看）"
            btn.text = "重新写一份"
        }
        btn.isEnabled = true
    }

    /** 手动生成（端侧要跑 30-60 s，期间把卡片变成进度态）。 */
    private fun generateDigest() {
        val btn = findViewById<Button>(R.id.btnDigest)
        val tv = findViewById<TextView>(R.id.tvDigest)
        val meta = findViewById<TextView>(R.id.tvDigestMeta)
        btn.isEnabled = false
        tv.text = "正在写今天的总结…\n（端侧模型独占运行，第一次还要先加载约 2.4 GB 模型）"
        meta.text = "生成中 · 数据不出手机"
        uiScope.launch {
            val r = withContext(Dispatchers.IO) {
                DailySummary.generate(this@MainActivity, db, force = true)
            }
            if (r.ok) {
                refreshDigest()
                Toast.makeText(this@MainActivity, "总结已生成并留档", Toast.LENGTH_SHORT).show()
            } else {
                refreshDigest()
                tv.text = "这次没写成：${r.error}"
                meta.text = "端侧模型忙或数据不够时可稍后再试；实在不行点「历史总结」看以前的"
                btn.isEnabled = true
            }
        }
    }

    /**
     * 底栏「＋记一笔」：别的页面过来时带 EXTRA_ADD，复用本实例（清单里 singleTop）后再弹窗。
     * 延迟 150ms 是因为 onNewIntent 时布局可能还在重建，直接 show 会挂在错误的位置。
     */
    private fun handleNavIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(BottomNav.EXTRA_ADD, false) == true) {
            intent.removeExtra(BottomNav.EXTRA_ADD)
            window.decorView.postDelayed({ openAddDialog() }, 150)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavIntent(intent)
    }

    /** 记一笔弹窗（底栏中央圆钮 / 首页按钮都走这里）。 */
    fun openAddDialog() {
        val input = EditText(this)
        input.hint = "金额（如 86.5）"
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

        AlertDialog.Builder(this)
            .setTitle("记一笔")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val amount = input.text.toString().toDoubleOrNull() ?: 0.0
                if (amount > 0) {
                    db.addRecord(
                        amount = amount,
                        category = "餐饮",
                        merchant = "手动",
                        note = if (insight.isNightNow()) "夜间记录" else ""
                    )
                    refresh()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

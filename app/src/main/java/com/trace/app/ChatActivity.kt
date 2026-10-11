package com.trace.app

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.minicpm_v_demo.ModelInfo
import com.trace.app.ai.GlmApi
import com.trace.app.behavior.ActivityLabel
import com.trace.app.db.TraceDbHelper
import com.trace.app.ui.BottomNav
import com.trace.app.util.AppNames
import com.trace.app.vlm.ModelDownloader
import com.trace.app.vlm.TextModel
import com.trace.app.vlm.VlmEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 助手。
 *
 * **助手 = 图片 + 语言**，两个大脑按**用户给的数据**分工（[VlmEngine.assistant]）：
 * - 带图提问 → 视觉模型（只有它长了眼睛）；
 * - 纯文字提问 → 文本模型（快、写中文稳）。
 *
 * 云端 GLM（[GlmApi]）是可选升级项——需要使用者自己在设置里填 API Key；
 * 带图 + 云端会切到 glm-4v-flash（免费的视觉版）。
 *
 * 两种大脑共用同一份「行为数据 + 消费流水 + 使用时长」上下文，只是云端走 messages，
 * 端侧把所有内容铺成一段纯文本提示词（端侧没有 system role 可用）。
 */
class ChatActivity : AppCompatActivity() {

    private lateinit var db: TraceDbHelper
    private lateinit var prefs: SharedPreferences
    private lateinit var chatContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var input: EditText
    private lateinit var btnSend: Button
    private lateinit var subtitle: TextView
    private lateinit var attachBar: LinearLayout
    private lateinit var attachThumb: ImageView
    private lateinit var attachHint: TextView

    /** 待发送的图片（JPEG 字节）。null = 纯文字提问 → 走文本模型。 */
    private var attachJpeg: ByteArray? = null

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) loadAttached(uri)
        }

    /** 已完成的问答轮次（本地与云端共用）。 */
    private val turns = mutableListOf<Pair<String, String>>()
    private var busy = false
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        BottomNav.bind(this, BottomNav.CHAT)

        db = TraceDbHelper(this)
        prefs = getSharedPreferences("glm_config", MODE_PRIVATE)

        chatContainer = findViewById(R.id.chatContainer)
        scrollView = findViewById(R.id.chatScroll)
        input = findViewById(R.id.chatInput)
        btnSend = findViewById(R.id.btnSend)
        subtitle = findViewById(R.id.tvChatSubtitle)
        attachBar = findViewById(R.id.attachBar)
        attachThumb = findViewById(R.id.attachThumb)
        attachHint = findViewById(R.id.attachHint)

        findViewById<Button>(R.id.btnChatSettings).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.btnAttach).setOnClickListener { pickImage.launch("image/*") }
        findViewById<Button>(R.id.btnAttachClear).setOnClickListener { clearAttach() }
        btnSend.setOnClickListener { trySend() }

        refreshSubtitle()
        loadHistory()
    }

    /**
     * 把相册里选的图读进来：**先缩到长边 1024 再转 JPEG**。
     *
     * 视觉模型的 prefill 耗时与像素数成正比，相册原图（4000px 级）直接喂会等很久；
     * 屏幕理解的截图本来就只有一两千像素，所以这里跟它对齐即可。
     */
    private fun loadAttached(uri: Uri) {
        Thread {
            try {
                val raw = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    ?: throw IllegalStateException("读不到这张图")
                val scaled = downscale(raw, 1024)
                val bos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, bos)
                val bytes = bos.toByteArray()
                if (scaled !== raw) raw.recycle()
                runOnUiThread {
                    attachJpeg = bytes
                    attachThumb.setImageBitmap(scaled)
                    attachHint.text = "带图提问会交给视觉模型看图（${bytes.size / 1024} KB，数据不出手机）"
                    attachBar.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                runOnUiThread { addBubble("ai", "❌ 这张图没读进来：${e.message}") }
            }
        }.start()
    }

    private fun downscale(src: Bitmap, maxEdge: Int): Bitmap {
        val long = maxOf(src.width, src.height)
        if (long <= maxEdge) return src
        val k = maxEdge.toFloat() / long
        return Bitmap.createScaledBitmap(src, (src.width * k).toInt(), (src.height * k).toInt(), true)
    }

    private fun clearAttach() {
        attachJpeg = null
        attachThumb.setImageDrawable(null)
        attachBar.visibility = View.GONE
    }

    /**
     * 聊天记录留档：进页面先把历史铺回来（数据在本机 chat_messages 表里），
     * 一条都没有才显示欢迎语。顺带重建 [turns]，云端多轮上下文才不会断。
     */
    private fun loadHistory() {
        val history = db.recentChatMessages(60)
        if (history.isEmpty()) {
            welcome()
            return
        }
        addBubble("ai", "📜 下面是我们的聊天记录（本机留档共 ${db.chatMessageCount()} 条）——" +
            "点右上角 ⚙️ 可以清空。")
        history.forEach { m ->
            if (m.role == "user") addBubble("me", m.text) else addBubble("ai", m.text)
        }
        history.forEachIndexed { i, m ->
            if (m.role == "user" && i + 1 < history.size && history[i + 1].role != "user") {
                turns.add(m.text to history[i + 1].text)
            }
        }
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun isLocal(): Boolean = prefs.getString(KEY_ENGINE, ENGINE_LOCAL) != ENGINE_CLOUD

    private fun refreshSubtitle() {
        subtitle.text = if (isLocal()) {
            "端侧双模型 · 文字问文本模型 / 带图问视觉模型（不联网）"
        } else {
            "已注入你的行为数据 · 云端 GLM"
        }
    }

    // ===== 欢迎语 =====

    private fun welcome() {
        if (isLocal()) {
            addBubble(
                "ai",
                "👋 我是 Trace 的助手，**默认跑在手机本地**（端侧模型），你的行为数据不出手机。\n\n" +
                    "🧩 两个大脑按你给的东西分工：\n" +
                    "· 只打字 → 文本模型回答（快、写中文稳）\n" +
                    "· 带一张图（左下角 🖼）→ 视觉模型看图回答\n" +
                    "两者会自动切换，第一次各自要加载一次（几秒到十几秒）。\n\n" +
                    "试试：\n· 我今天都干了什么？\n· 分析一下我的消费习惯\n· 发一张外卖订单截图，问「这单多少钱、几点下的」\n\n" +
                    "想用云端大脑（更快）：点右上角 ⚙️ 切到「云端 GLM」，并填自己的 API Key。"
            )
        } else if (prefs.getString("api_key", "").isNullOrBlank()) {
            addBubble(
                "ai",
                "☁️ 你选的是云端大脑，但还没配置 API Key。点右上角 ⚙️ 设置：\n" +
                    "1. 手机浏览器打开 bigmodel.cn 注册\n" +
                    "2. 控制台 → API Keys → 复制\n" +
                    "3. 粘贴到设置里即可对话（glm-4-flash 免费）\n\n" +
                    "不想配 Key 就切回「端侧模型」——本机免费、离线、数据不出手机。"
            )
        } else {
            addBubble(
                "ai",
                "👋 我是 Trace 的助手，已经读过你今天的行为数据。想聊什么？\n\n试试：\n· 我今天都干了什么？\n· 分析一下我的消费习惯\n· 我晚上一般在玩什么 App？"
            )
        }
    }

    // ===== 发送与回复 =====

    private fun trySend() {
        if (busy) return
        val typed = input.text.toString().trim()
        val img = attachJpeg
        if (typed.isEmpty() && img == null) return

        val apiKey = prefs.getString("api_key", "") ?: ""
        if (!isLocal() && apiKey.isBlank()) {
            addBubble("ai", "☁️ 还没有 API Key。点右上角 ⚙️ 填上，或切回「端侧模型」（免费、离线、不出手机）。")
            showSettings()
            return
        }

        // 只发了图没打字：给个兜底问题，不然模型不知道要它干什么
        val question =
            if (typed.isEmpty() && img != null) "看看这张图，说说里面有什么值得注意的。"
            else typed

        input.text.clear()
        clearAttach()
        addBubble("me", if (img != null) "🖼 图片" + if (typed.isEmpty()) "" else "\n$typed" else typed)
        db.addChatMessage("user", if (img != null) "[图片] $question" else question, if (isLocal()) "local" else "cloud")
        if (isLocal()) sendLocal(question, img) else sendCloud(question, apiKey, img)
    }

    /**
     * 端侧大脑：**按用户给的数据分工**——带图走视觉模型、纯文字走文本模型
     * （[VlmEngine.assistant] 内部决定），逐 token 回填气泡。
     */
    private fun sendLocal(question: String, jpeg: ByteArray?) {
        busy = true
        btnSend.text = "…"
        val vision = jpeg != null
        val tip = when {
            vision && !VlmEngine.isModelLoaded() -> "🧠 先加载视觉模型，再看图回答…"
            vision -> "🧠 视觉模型看图回答中…"
            VlmEngine.isModelLoaded() -> "🧠 端侧文本模型思考中…"
            else -> "🧠 首次要先加载端侧模型，等几秒…"
        }
        val bubble = addBubble("ai", tip, tag = "pending")
        val t0 = System.currentTimeMillis()
        uiScope.launch {
            val prompt = buildLocalPrompt(question)
            var started = false
            val r = VlmEngine.assistant(this@ChatActivity, prompt, jpeg) { tok ->
                runOnUiThread {
                    if (!started) {
                        started = true
                        bubble.text = ""
                    }
                    bubble.text = bubble.text.toString() + tok
                    scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                }
            }
            val sec = (System.currentTimeMillis() - t0) / 1000.0
            bubble.tag = null
            val who =
                if (vision) "端侧 · 视觉模型"
                else "端侧 · " + VlmEngine.chatModelName(this@ChatActivity)
            val answer = when {
                !r.ok && r.error == "busy" -> "🧠 端侧模型正忙着（正在跑屏幕理解）。等它跑完这一帧再问一次。"
                !r.ok -> "❌ 端侧模型没跑通：${r.error}\n\n可以试：\n· 首页「端侧屏幕理解」里先加载一次模型\n· 或 ⚙️ 切到云端大脑"
                bubble.text.toString().isBlank() -> "（模型没有输出内容）"
                else -> "${bubble.text}\n\n— $who · ${String.format(Locale.CHINA, "%.1f", sec)} s"
            }
            bubble.text = answer
            if (r.ok) {
                val pure = answer.substringBefore("\n\n— ")
                turns.add(question to pure)
                db.addChatMessage("ai", pure, "local")
            }
            busy = false
            btnSend.text = "发送"
        }
    }

    /** 云端大脑：GLM（需要 API Key）；带图自动切到免费的视觉版 glm-4v-flash。 */
    private fun sendCloud(question: String, apiKey: String, jpeg: ByteArray?) {
        busy = true
        btnSend.text = "…"
        val bubble = addBubble("ai", "思考中…", tag = "pending")
        val model = if (jpeg != null) "glm-4v-flash"
        else prefs.getString("model", "glm-4-flash") ?: "glm-4-flash"
        val t0 = System.currentTimeMillis()

        Thread {
            try {
                val reply = GlmApi.chat(apiKey, model, buildCloudMessages(question, jpeg))
                val sec = (System.currentTimeMillis() - t0) / 1000.0
                runOnUiThread {
                    bubble.tag = null
                    bubble.text = "$reply\n\n— 云端 $model · ${String.format(Locale.CHINA, "%.1f", sec)} s"
                    turns.add(question to reply)
                    db.addChatMessage("ai", reply, "cloud")
                    busy = false
                    btnSend.text = "发送"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    bubble.tag = null
                    bubble.text = "❌ 调用失败：${e.message}\n\n请检查：\n· API Key 是否正确（bigmodel.cn 控制台）\n· 账户是否有额度\n· 手机网络"
                    busy = false
                    btnSend.text = "发送"
                }
            }
        }.start()
    }

    /** 云端要 messages 结构：[system] + 历史 + 本轮问题（带图时问题挂图）。 */
    private fun buildCloudMessages(question: String, jpeg: ByteArray? = null): List<GlmApi.Message> {
        val msgs = mutableListOf(GlmApi.Message("system", composeDataBlock()))
        turns.takeLast(6).forEach { (q, a) ->
            msgs.add(GlmApi.Message("user", q))
            msgs.add(GlmApi.Message("assistant", a))
        }
        msgs.add(GlmApi.Message("user", question, jpeg))
        return msgs
    }

    /** 端侧只能给一段纯文本：数据 + 历史 + 问题全铺开。 */
    private fun buildLocalPrompt(question: String): String = buildString {
        appendLine(composeDataBlock())
        if (turns.isNotEmpty()) {
            appendLine("===== 最近的对白 =====")
            turns.takeLast(6).forEach { (q, a) ->
                appendLine("用户：$q")
                appendLine("助手：${a.take(200)}")
            }
            appendLine()
        }
        appendLine("===== 用户现在问 =====")
        appendLine(question)
    }

    /** 两种大脑共用的数据上下文。 */
    private fun composeDataBlock(): String {
        val fmt = SimpleDateFormat("HH:mm", Locale.CHINA)
        val dfmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        return buildString {
            appendLine("===== 今日行为事件流（最近30条：时间 / 应用 / 在干什么 / 事件）=====")
            db.recentUsageEvents(30).reversed().forEach { e ->
                appendLine("${fmt.format(Date(e.timestamp))}  ${name(e.pkg)}  ${ActivityLabel.describe(e.pkg, e.activity)}  [${typeLabel(e.type)}]")
            }
            appendLine()
            appendLine("===== 应用内行为（最近10条）=====")
            db.recentActivityLog(10).reversed().forEach { a ->
                val detail = if (a.detail.isNullOrBlank()) "" else " — ${a.detail}"
                appendLine("${fmt.format(Date(a.timestamp))}  ${name(a.pkg)} · ${a.what}$detail")
            }
            appendLine()
            appendLine("===== 内容事件统计 =====")
            appendLine(
                "点赞 ${db.contentEventCountOf("LIKE")} 次 · 收藏 ${db.contentEventCountOf("SAVE")} 次 · " +
                    "换视频 ${db.contentEventCountOf("VIDEO_CHANGED")} 次"
            )
            appendLine()
            appendLine("===== 消费流水（最近10笔）=====")
            db.allRecords().take(10).forEach { r ->
                appendLine("${dfmt.format(Date(r.timestamp))}  ¥${r.amount}  ${r.category}  ${r.merchant ?: ""}${if (r.isNight) "  (深夜)" else ""}")
            }
            appendLine()
            appendLine("===== App 使用时长（近7天 Top5）=====")
            db.behaviorByDay(7).forEach { (pkg, ms) ->
                appendLine("${name(pkg)}: ${ms / 60000} 分钟")
            }
        }
    }

    /** 包名 → 中文应用名（与行为统计页同一个来源）。 */
    private fun name(pkg: String?): String = AppNames.of(this, pkg)

    private fun typeLabel(type: String?): String = when (type) {
        "RESUMED" -> "进入"
        "PAUSED" -> "离开"
        "SCREEN_OFF" -> "熄屏"
        else -> type ?: ""
    }

    // ===== 气泡 UI =====

    private fun addBubble(who: String, text: String, tag: String? = null): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 14f
        tv.setLineSpacing(4f, 1f)
        tv.setPadding(dp(14), dp(10), dp(14), dp(10))
        tv.tag = tag

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
            if (who == "me") {
                gravity = Gravity.END
                tv.setBackgroundResource(R.drawable.bubble_me)
                // 颜色走资源：本机是暗色模式，写死 0xFF374151 会在深色气泡上变成"深灰压深灰"
                tv.setTextColor(resources.getColor(R.color.on_brand, theme))
                marginStart = dp(60)
            } else {
                gravity = Gravity.START
                tv.setBackgroundResource(R.drawable.bubble_ai)
                tv.setTextColor(resources.getColor(R.color.text, theme))
                marginEnd = dp(60)
            }
        }
        chatContainer.addView(tv, lp)
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        return tv
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ===== 设置 =====

    private fun showSettings() {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        val pad = dp(20)
        layout.setPadding(pad, dp(10), pad, 0)

        val group = RadioGroup(this)
        val rbLocal = RadioButton(this).apply {
            id = ID_RB_LOCAL
            text = "端侧模型（默认 · 免费 · 离线 · 数据不出手机）"
        }
        val rbCloud = RadioButton(this).apply {
            id = ID_RB_CLOUD
            text = "云端 GLM（更快，需自己填 API Key）"
        }
        group.addView(rbLocal)
        group.addView(rbCloud)
        group.check(if (isLocal()) ID_RB_LOCAL else ID_RB_CLOUD)
        layout.addView(group)

        val keyInput = EditText(this)
        keyInput.hint = "API Key（bigmodel.cn 控制台获取，仅云端用到）"
        keyInput.setText(prefs.getString("api_key", ""))
        keyInput.inputType = InputType.TYPE_CLASS_TEXT
        layout.addView(keyInput)

        val modelInput = EditText(this)
        modelInput.hint = "云端模型名"
        modelInput.setText(prefs.getString("model", "glm-4-flash"))
        layout.addView(modelInput)

        val pick = Button(this)
        pick.text = "文本模型：${TextModel.selected(this).displayName}"
        pick.setOnClickListener { showTextModelPicker() }
        layout.addView(pick)

        val tip = TextView(this)
        tip.text = "端侧是一个视觉模型 + 一个文本模型分工：\n" +
            "· 屏幕理解、带图提问 → 视觉模型（MiniCPM-V）\n" +
            "· 写总结、纯文字提问 → 上面这个文本模型\n" +
            "两者共用一个 native 引擎，会互相排队、切换时重新加载。\n" +
            "云端可选：glm-4-flash（免费）/ glm-4v-flash（视觉）。"
        tip.textSize = 12f
        tip.setPadding(0, dp(8), 0, 0)
        layout.addView(tip)

        AlertDialog.Builder(this)
            .setTitle("AI 设置")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val local = group.checkedRadioButtonId == ID_RB_LOCAL
                prefs.edit()
                    .putString(KEY_ENGINE, if (local) ENGINE_LOCAL else ENGINE_CLOUD)
                    .putString("api_key", keyInput.text.toString().trim())
                    .putString("model", modelInput.text.toString().trim().ifBlank { "glm-4-flash" })
                    .apply()
                refreshSubtitle()
                addBubble(
                    "ai",
                    if (local) "✅ 已切到端侧模型（本机推理，数据不出手机）。"
                    else "✅ 已切到云端 GLM：${modelInput.text.toString().trim().ifBlank { "glm-4-flash" }}。"
                )
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("清空聊天记录") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("清空聊天记录？")
                    .setMessage("本机已留档 ${db.chatMessageCount()} 条，清空后不可恢复。")
                    .setPositiveButton("清空") { _, _ ->
                        db.clearChatMessages()
                        turns.clear()
                        chatContainer.removeAllViews()
                        welcome()
                    }
                    .setNegativeButton("算了", null)
                    .show()
            }
            .show()
    }

    /**
     * 文本模型选择器：写总结 / 纯文字提问用哪颗脑子。
     * 候选都来自 [TextModel.candidates]；没下载的点了就走 App 内下载（断点续传）。
     */
    private fun showTextModelPicker() {
        val list = TextModel.candidates
        val cur = TextModel.selected(this)
        val labels = list.map { m ->
            val state = when {
                m.id == cur.id && TextModel.ready(this, m) -> "使用中"
                TextModel.ready(this, m) -> "已下载（点它切换）"
                else -> "未下载 · 约 " + TextModel.sizeText(this, m)
            }
            "${m.displayName} · $state"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("文本模型（总结 / 聊天）")
            .setItems(labels) { _, which ->
                val m = list[which]
                if (TextModel.ready(this, m)) {
                    TextModel.select(this, m.id)
                    addBubble("ai", "✅ 已切到 **${m.displayName}**：之后的总结和纯文字提问都用它（带图仍走视觉模型）。")
                } else {
                    confirmDownload(m)
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun confirmDownload(m: ModelInfo) {
        val freeGb = ModelDownloader.freeBytes(this) / 1024.0 / 1024.0 / 1024.0
        AlertDialog.Builder(this)
            .setTitle("下载 ${m.displayName}？")
            .setMessage(
                "大小约 ${TextModel.sizeText(this, m)}；手机内部存储剩余 " +
                    String.format(Locale.CHINA, "%.1f", freeGb) + " GB。\n\n" +
                    "在 App 内直接下（ModelScope 优先，失败自动换源，断了下次能续传）。"
            )
            .setPositiveButton("下载") { _, _ -> downloadModel(m) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun downloadModel(m: ModelInfo) {
        val box = TextView(this).apply {
            text = "准备下载 ${m.displayName}…"
            textSize = 14f
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        AlertDialog.Builder(this)
            .setTitle("正在下载 ${m.displayName}")
            .setView(box)
            .setNegativeButton("收起来（继续下）", null)
            .show()
        uiScope.launch(Dispatchers.IO) {
            try {
                ModelDownloader.download(this@ChatActivity, m) { done, total, src ->
                    val doneMb = done / 1024 / 1024
                    val totalMb = if (total > 0) (total / 1024 / 1024).toString() else "?"
                    val pct = if (total > 0) "（${done * 100 / total}%）" else ""
                    runOnUiThread { box.text = "$src\n$doneMb / $totalMb MB $pct" }
                }
                runOnUiThread {
                    box.text = "✅ 下载完成，已启用"
                    TextModel.select(this@ChatActivity, m.id)
                    addBubble("ai", "✅ **${m.displayName}** 下载完成并已启用（总结 / 聊天就用它）。")
                }
            } catch (e: Exception) {
                runOnUiThread { box.text = "❌ 下载失败：${e.message}\n可以关掉重试，已下的部分会续传。" }
            }
        }
    }

    private companion object {
        const val KEY_ENGINE = "engine"
        const val ENGINE_LOCAL = "local"
        const val ENGINE_CLOUD = "cloud"
        const val ID_RB_LOCAL = 1001
        const val ID_RB_CLOUD = 1002
    }
}

package com.trace.app

import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.trace.app.ai.GlmApi
import com.trace.app.behavior.ActivityLabel
import com.trace.app.db.TraceDbHelper
import com.trace.app.util.AppNames
import com.trace.app.vlm.VlmEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 助手。
 *
 * **默认走端侧模型**（[VlmEngine.ask]，纯文本、不喂图、不联网、数据不出手机）；
 * 云端 GLM（[GlmApi]）是可选升级项——需要使用者自己在设置里填 API Key。
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

    /** 已完成的问答轮次（本地与云端共用）。 */
    private val turns = mutableListOf<Pair<String, String>>()
    private var busy = false
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        db = TraceDbHelper(this)
        prefs = getSharedPreferences("glm_config", MODE_PRIVATE)

        chatContainer = findViewById(R.id.chatContainer)
        scrollView = findViewById(R.id.chatScroll)
        input = findViewById(R.id.chatInput)
        btnSend = findViewById(R.id.btnSend)
        subtitle = findViewById(R.id.tvChatSubtitle)

        findViewById<Button>(R.id.btnChatSettings).setOnClickListener { showSettings() }
        btnSend.setOnClickListener { trySend() }

        refreshSubtitle()
        welcome()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun isLocal(): Boolean = prefs.getString(KEY_ENGINE, ENGINE_LOCAL) != ENGINE_CLOUD

    private fun refreshSubtitle() {
        subtitle.text = if (isLocal()) {
            "已注入你的行为数据 · 端侧模型（不联网）"
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
                    "⏱ 第一次提问会先加载模型（约 2.4 GB），要等十几秒；之后一轮回答通常几秒到几十秒。\n\n" +
                    "试试：\n· 我今天都干了什么？\n· 分析一下我的消费习惯\n· 我晚上一般在玩什么 App？\n\n" +
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
        val text = input.text.toString().trim()
        if (text.isEmpty()) return

        val apiKey = prefs.getString("api_key", "") ?: ""
        if (!isLocal() && apiKey.isBlank()) {
            addBubble("ai", "☁️ 还没有 API Key。点右上角 ⚙️ 填上，或切回「端侧模型」（免费、离线、不出手机）。")
            showSettings()
            return
        }

        input.text.clear()
        addBubble("me", text)
        if (isLocal()) sendLocal(text) else sendCloud(text, apiKey)
    }

    /** 端侧大脑：纯文本提示词 → 本地模型，逐 token 回填气泡。 */
    private fun sendLocal(question: String) {
        busy = true
        btnSend.text = "…"
        val bubble = addBubble(
            "ai",
            if (VlmEngine.isModelLoaded()) "🧠 端侧模型思考中…" else "🧠 首次要先加载端侧模型（约 2.4 GB），等十几秒…",
            tag = "pending"
        )
        val t0 = System.currentTimeMillis()
        uiScope.launch {
            val prompt = buildLocalPrompt(question)
            var started = false
            val r = VlmEngine.ask(this@ChatActivity, prompt) { tok ->
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
            val answer = when {
                !r.ok && r.error == "busy" -> "🧠 端侧模型正忙着（正在跑屏幕理解）。等它跑完这一帧再问一次。"
                !r.ok -> "❌ 端侧模型没跑通：${r.error}\n\n可以试：\n· 首页「端侧屏幕理解」里先加载一次模型\n· 或 ⚙️ 切到云端大脑"
                bubble.text.toString().isBlank() -> "（模型没有输出内容）"
                else -> "${bubble.text}\n\n— 端侧 · ${String.format(Locale.CHINA, "%.1f", sec)} s"
            }
            bubble.text = answer
            if (r.ok) turns.add(question to answer.substringBefore("\n\n— 端侧"))
            busy = false
            btnSend.text = "发送"
        }
    }

    /** 云端大脑：GLM（需要 API Key）。 */
    private fun sendCloud(question: String, apiKey: String) {
        busy = true
        btnSend.text = "…"
        val bubble = addBubble("ai", "思考中…", tag = "pending")
        val model = prefs.getString("model", "glm-4-flash") ?: "glm-4-flash"
        val t0 = System.currentTimeMillis()

        Thread {
            try {
                val reply = GlmApi.chat(apiKey, model, buildCloudMessages(question))
                val sec = (System.currentTimeMillis() - t0) / 1000.0
                runOnUiThread {
                    bubble.tag = null
                    bubble.text = "$reply\n\n— 云端 $model · ${String.format(Locale.CHINA, "%.1f", sec)} s"
                    turns.add(question to reply)
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

    /** 云端要 messages 结构：[system] + 历史 + 本轮问题。 */
    private fun buildCloudMessages(question: String): List<GlmApi.Message> {
        val msgs = mutableListOf(GlmApi.Message("system", composeDataBlock()))
        turns.takeLast(6).forEach { (q, a) ->
            msgs.add(GlmApi.Message("user", q))
            msgs.add(GlmApi.Message("assistant", a))
        }
        msgs.add(GlmApi.Message("user", question))
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

        val tip = TextView(this)
        tip.text = "端侧模型：本机 MiniCPM-V（同一个模型也用来做屏幕理解，两者会互相排队）。\n" +
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
            .show()
    }

    private companion object {
        const val KEY_ENGINE = "engine"
        const val ENGINE_LOCAL = "local"
        const val ENGINE_CLOUD = "cloud"
        const val ID_RB_LOCAL = 1001
        const val ID_RB_CLOUD = 1002
    }
}

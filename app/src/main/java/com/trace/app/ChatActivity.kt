package com.trace.app

import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.trace.app.ai.GlmApi
import com.trace.app.db.TraceDbHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 聊天窗口：以 GLM 系模型为大脑，注入本机行为数据作为上下文
 * - API key / 模型在设置弹窗配置，存 SharedPreferences
 * - 每次对话自动附带：今日行为事件流 + 近期消费 + App 使用时长 Top
 */
class ChatActivity : AppCompatActivity() {

    private lateinit var db: TraceDbHelper
    private lateinit var prefs: SharedPreferences
    private lateinit var chatContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var input: EditText
    private lateinit var btnSend: Button

    private val history = mutableListOf<GlmApi.Message>()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        db = TraceDbHelper(this)
        prefs = getSharedPreferences("glm_config", MODE_PRIVATE)

        chatContainer = findViewById(R.id.chatContainer)
        scrollView = findViewById(R.id.chatScroll)
        input = findViewById(R.id.chatInput)
        btnSend = findViewById(R.id.btnSend)

        findViewById<Button>(R.id.btnChatSettings).setOnClickListener { showSettings() }
        btnSend.setOnClickListener { trySend() }

        // 欢迎语
        if (prefs.getString("api_key", "").isNullOrBlank()) {
            addBubble(
                "ai",
                "👋 我是 Trace 的 AI 助手，可以基于你的行为数据聊天。\n\n" +
                    "⚠️ 还没配置 API Key：点右上角 ⚙️ 设置\n" +
                    "1. 手机浏览器打开 bigmodel.cn 注册\n" +
                    "2. 控制台 → API Keys → 复制\n" +
                    "3. 粘贴到设置里即可对话\n\n" +
                    "（glm-4-flash 模型免费；autoglm-phone 目前限时免费）"
            )
        } else {
            addBubble("ai", "👋 我是 Trace 的 AI 助手，已经读过你今天的行为数据。想聊什么？\n\n试试：\n· 我今天都干了什么？\n· 分析一下我的消费习惯\n· 我晚上一般在玩什么 App？")
        }
    }

    // ===== 发送与回复 =====

    private fun trySend() {
        if (busy) return
        val text = input.text.toString().trim()
        if (text.isEmpty()) return

        val apiKey = prefs.getString("api_key", "") ?: ""
        if (apiKey.isBlank()) {
            showSettings()
            return
        }

        input.text.clear()
        addBubble("me", text)
        history.add(GlmApi.Message("user", text))
        busy = true
        btnSend.text = "…"
        addBubble("ai", "思考中…", tag = "pending")

        val model = prefs.getString("model", "glm-4-flash") ?: "glm-4-flash"

        Thread {
            try {
                val messages = buildContextMessages()
                val reply = GlmApi.chat(apiKey, model, messages)
                runOnUiThread {
                    removePending()
                    addBubble("ai", reply)
                    history.add(GlmApi.Message("assistant", reply))
                    busy = false
                    btnSend.text = "发送"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    removePending()
                    addBubble("ai", "❌ 调用失败：${e.message}\n\n请检查：\n· API Key 是否正确（bigmodel.cn 控制台）\n· 账户是否有额度\n· 手机网络")
                    busy = false
                    btnSend.text = "发送"
                }
            }
        }.start()
    }

    /** 组装上下文：系统提示（含行为数据）+ 最近对话 */
    private fun buildContextMessages(): List<GlmApi.Message> {
        val system = buildString {
            appendLine("你是「Trace」App 的 AI 助手。Trace 记录了用户的手机使用行为和消费流水。")
            appendLine("你的职责：基于下面的真实数据，回答用户关于自己行为和消费的问题，语气自然、说人话、有洞察。")
            appendLine("分析时给出具体数字和时间，别泛泛而谈。如果数据不足以回答，就直说。")
            appendLine()
            appendLine("===== 用户今日行为事件流（最近30条，时间/应用/页面/事件类型）=====")
            val fmt = SimpleDateFormat("HH:mm", Locale.CHINA)
            db.recentUsageEvents(30).reversed().forEach { e ->
                appendLine("${fmt.format(Date(e.timestamp))}  ${friendlyName(e.pkg)}  ${e.activity}  [${e.type}]")
            }
            appendLine()
            appendLine("===== 用户消费流水（最近10笔）=====")
            val dfmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
            db.allRecords().take(10).forEach { r ->
                appendLine("${dfmt.format(Date(r.timestamp))}  ¥${r.amount}  ${r.category}  ${r.merchant ?: ""}  ${if (r.isNight) "(深夜)" else ""}")
            }
            appendLine()
            appendLine("===== App 使用时长统计（近7天 Top5）=====")
            db.behaviorByDay(7).forEach { (pkg, ms) ->
                appendLine("${friendlyName(pkg)}: ${ms / 60000} 分钟")
            }
        }

        val msgs = mutableListOf(GlmApi.Message("system", system))
        // 最近 12 轮对话，避免超出上下文
        msgs.addAll(history.takeLast(12))
        return msgs
    }

    private fun friendlyName(pkg: String): String = when {
        pkg.contains("aweme") -> "抖音"
        pkg.contains("tencent.mm") -> "微信"
        pkg.contains("mobileqq") -> "QQ"
        pkg.contains("taobao") -> "淘宝"
        pkg.contains("Alipay") -> "支付宝"
        pkg.contains("BaiduMap") -> "百度地图"
        pkg.contains("bili") -> "B站"
        pkg.contains("weibo") -> "微博"
        pkg.contains("pinduoduo") -> "拼多多"
        pkg.contains("jingdong") -> "京东"
        pkg.contains("meituan") -> "美团"
        pkg.contains("zqb") || pkg.contains("sgame") -> "游戏"
        pkg.contains("settings") -> "设置"
        pkg.contains("launcher") -> "桌面"
        pkg.contains("trace.app") -> "Trace(本App)"
        else -> pkg.substringAfterLast('.')
    }

    // ===== 气泡 UI =====

    private fun addBubble(who: String, text: String, tag: String? = null) {
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
    }

    private fun removePending() {
        for (i in chatContainer.childCount - 1 downTo 0) {
            if (chatContainer.getChildAt(i).tag == "pending") {
                chatContainer.removeViewAt(i)
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ===== 设置 =====

    private fun showSettings() {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        val pad = dp(20)
        layout.setPadding(pad, dp(10), pad, 0)

        val keyInput = EditText(this)
        keyInput.hint = "API Key（bigmodel.cn 控制台获取）"
        keyInput.setText(prefs.getString("api_key", ""))
        keyInput.inputType = InputType.TYPE_CLASS_TEXT
        layout.addView(keyInput)

        val modelInput = EditText(this)
        modelInput.hint = "模型名"
        modelInput.setText(prefs.getString("model", "glm-4-flash"))
        layout.addView(modelInput)

        val tip = TextView(this)
        tip.text = "可选模型：\n· glm-4-flash（免费，推荐日常对话）\n· autoglm-phone（限时免费，手机任务执行）\n· glm-4v-flash（免费，视觉理解）"
        tip.textSize = 12f
        tip.setPadding(0, dp(8), 0, 0)
        layout.addView(tip)

        AlertDialog.Builder(this)
            .setTitle("AI 设置")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                prefs.edit()
                    .putString("api_key", keyInput.text.toString().trim())
                    .putString("model", modelInput.text.toString().trim().ifBlank { "glm-4-flash" })
                    .apply()
                if (chatContainer.childCount <= 1) recreate()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

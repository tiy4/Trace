package com.trace.app.ai

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 智谱 BigModel API 客户端（OpenAI 兼容格式）
 * 文档: https://open.bigmodel.cn/api/paas/v4/chat/completions
 * 可选模型: glm-4-flash(免费对话) / autoglm-phone(任务执行) / glm-4v-flash(视觉,免费)
 */
object GlmApi {

    private const val BASE_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions"

    /**
     * 一条消息。[imageJpeg] 不为空时按视觉格式发（content 变成
     * `[{type:image_url,...},{type:text,...}]`），只有 glm-4v-flash 这类视觉模型认。
     */
    data class Message(val role: String, val content: String, val imageJpeg: ByteArray? = null)

    /** 同步调用，必须在后台线程执行。返回模型回复文本；失败抛异常带原因。 */
    fun chat(apiKey: String, model: String, messages: List<Message>): String {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                messages.forEach { m ->
                    put(JSONObject().apply {
                        put("role", m.role)
                        put("content", contentOf(m))
                    })
                }
            })
            put("temperature", 0.6)
            put("max_tokens", 1024)
            put("stream", false)
        }

        val conn = URL(BASE_URL).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            if (code !in 200..299) {
                val errMsg = try {
                    JSONObject(resp).optJSONObject("error")?.optString("message") ?: resp.take(300)
                } catch (e: Exception) { resp.take(300) }
                throw RuntimeException("HTTP $code: $errMsg")
            }

            val json = JSONObject(resp)
            json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } finally {
            conn.disconnect()
        }
    }

    /** 纯文本 → 字符串；带图 → OpenAI 视觉格式的 content 数组。 */
    private fun contentOf(m: Message): Any {
        val img = m.imageJpeg ?: return m.content
        return JSONArray().apply {
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put(
                    "url", "data:image/jpeg;base64," + Base64.encodeToString(img, Base64.NO_WRAP)
                ))
            })
            put(JSONObject().apply {
                put("type", "text")
                put("text", m.content)
            })
        }
    }
}

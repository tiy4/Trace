package com.trace.app.ai

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

    data class Message(val role: String, val content: String)

    /** 同步调用，必须在后台线程执行。返回模型回复文本；失败抛异常带原因。 */
    fun chat(apiKey: String, model: String, messages: List<Message>): String {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                messages.forEach { m ->
                    put(JSONObject().apply {
                        put("role", m.role)
                        put("content", m.content)
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
}

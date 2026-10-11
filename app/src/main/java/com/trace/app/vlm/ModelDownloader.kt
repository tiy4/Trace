package com.trace.app.vlm

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.example.minicpm_v_demo.ModelInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * 纯文本模型的下载器。
 *
 * 上游 demo 的下载器随它的模型管理界面一起被裁掉了（`LlamaEngine` 里只剩注释），
 * 所以这里自己写一个：**ModelScope 优先、HuggingFace 兜底、最后才是直链镜像**。
 * 顺序是有意的——这台机器在国内网络下，ModelScope 明显比 HF 稳；两个源提供的是
 * 同一份产物（git-LFS 对象），所以**失败可以跨源续传**：`.part` 文件一直留着，
 * 下一个源用 `Range: bytes=<已有>-` 接着下。
 *
 * 下完校验大小（`ModelInfo` 没登记 MD5 的模型就只对长度），然后改名成正式文件名。
 */
object ModelDownloader {

    private const val TAG = "TraceDl"
    private const val CONNECT_MS = 15_000
    private const val READ_MS = 60_000
    private const val BUF = 256 * 1024
    private const val USER_AGENT = "Trace/0.1 (Android)"

    /** 留 300 MB 余量，别把手机塞满。 */
    private const val SAFETY_BYTES = 300L * 1024 * 1024

    data class Src(val label: String, val url: String)

    private fun sources(m: ModelInfo): List<Src> = buildList {
        m.msRepo?.let {
            add(Src("ModelScope", "https://modelscope.cn/models/$it/resolve/${m.msBranch}/${m.ggufRemotePath}"))
        }
        m.hfRepo?.let {
            add(Src("HuggingFace", "https://huggingface.co/$it/resolve/${m.hfBranch}/${m.ggufRemotePath}"))
        }
        m.directGgufUrl?.let { add(Src("镜像", it)) }
    }

    fun freeBytes(ctx: Context): Long = try {
        val st = StatFs(ctx.filesDir.absolutePath)
        st.availableBytes
    } catch (t: Throwable) {
        -1L
    }

    /**
     * 下载 [m] 的 gguf 到内部私有目录，返回落地后的文件。
     * @param onProgress (已下载字节, 总字节或 -1, 当前来源名)
     */
    suspend fun download(
        ctx: Context,
        m: ModelInfo,
        onProgress: (Long, Long, String) -> Unit = { _, _, _ -> },
    ): File {
        val dest = TextModel.internalFile(ctx, m)
        val part = File(dest.absolutePath + ".part")
        dest.parentFile?.mkdirs()

        val srcs = sources(m)
        require(srcs.isNotEmpty()) { "模型 ${m.id} 没有配置下载源" }

        var lastError: String? = null
        for (s in srcs) {
            try {
                Log.i(TAG, "下载 ${m.id} ← ${s.label} 已有=${part.length()}B")
                fetch(s, part, onProgress)
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                Log.i(TAG, "下载完成 ${dest.absolutePath} ${dest.length()}B（来源 ${s.label}）")
                return dest
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                lastError = "${s.label}: ${t.message}"
                Log.w(TAG, "来源 ${s.label} 失败：${t.message}（保留 .part 以便续传）")
            }
        }
        throw IOException("下载失败 · " + (lastError ?: "没有可用下载源"))
    }

    /** 从单个来源把 [part] 补齐到完整文件；成功返回，失败抛异常。 */
    private suspend fun fetch(
        s: Src,
        part: File,
        onProgress: (Long, Long, String) -> Unit,
    ) {
        val have = if (part.exists()) part.length() else 0L
        val conn = (URL(s.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_MS
            readTimeout = READ_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            if (have > 0L) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")

            val respLen = conn.contentLengthLong
            val total = if (code == HttpURLConnection.HTTP_PARTIAL) have + respLen else respLen

            // 服务端不认 Range（返回 200）却本地已有残片 → 只能从头下。
            if (code == HttpURLConnection.HTTP_OK && have > 0L) {
                Log.w(TAG, "${s.label} 不支持断点续传，从头下（丢弃 ${have}B 残片）")
                part.delete()
            }

            var lastTick = 0L
            FileOutputStream(part, true).use { out ->
                conn.inputStream.use { ins ->
                    val buf = ByteArray(BUF)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        val done = part.length()
                        if (done - lastTick >= 2L * 1024 * 1024 || (total > 0L && done >= total)) {
                            lastTick = done
                            onProgress(done, total, s.label)
                        }
                    }
                    out.flush()
                }
            }

            val got = part.length()
            if (total > 0L && got != total) {
                throw IOException("大小不符：$got / $total")
            }
            if (got == 0L) throw IOException("下到 0 字节")
            onProgress(got, if (total > 0L) total else got, s.label)
        } finally {
            try {
                conn.disconnect()
            } catch (_: Throwable) {
            }
        }
    }
}

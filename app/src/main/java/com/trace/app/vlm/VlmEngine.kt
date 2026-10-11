package com.trace.app.vlm

import android.content.Context
import android.util.Log
import com.example.minicpm_v_demo.LlamaEngine
import com.example.minicpm_v_demo.LlamaState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 一次端侧分析的结果与耗时。 */
data class VlmResult(
    val ok: Boolean,
    val slice: Int,
    val loadMs: Long,
    val prefillMs: Long,
    val genMs: Long,
    val answer: String,
    val error: String? = null,
)

/**
 * 端侧 VLM 的门面：模型按需加载、串行推理、闲时释放。
 *
 * 底层复用官方 demo 的 [LlamaEngine]（JNI 符号固定，包名类名不可改）。
 * 该引擎自身是单槽串行（`Dispatchers.IO.limitedParallelism(1)`），
 * 这里再加一把互斥锁，避免截图服务与手动分析同时抢模型。
 */
object VlmEngine {

    private const val TAG = "TraceVlm"

    const val DEFAULT_PROMPT =
        "这是一张手机屏幕截图。用中文简短描述屏幕上正在发生什么，并读出其中关键的数字或文字。"

    /**
     * 屏幕理解的「系统级」指令。
     *
     * 注意：**不能**走原生 `processSystemPrompt()`——视觉路径下它会调用
     * `common_chat_format_single(ROLE_SYSTEM, ...)`，对本模型（MiniCPM-V 4.6 GGUF）
     * 直接抛 C++ 异常并 SIGABRT 整进程（真机 22:05:06 复现，栈顶
     * `libllama-common.so!common_chat_format_single` ← `processSystemPrompt`）。
     * 视觉路径的 `processUserPrompt` 是手工拼 `<|im_start|>user` 的，不碰模板，
     * 所以这里把指令并进用户提示词里。
     */
    private const val INSTRUCTION =
        "你是手机屏幕内容理解引擎。只描述屏幕上真实可见的内容，不要编造，不要输出与屏幕无关的推测。\n"

    /** 空闲多久后释放模型（毫秒）。 */
    private const val IDLE_UNLOAD_MS = 120_000L

    /** 等待 native 库异步加载完成的上限。 */
    private const val INIT_TIMEOUT_MS = 20_000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gate = Mutex()

    @Volatile private var engine: LlamaEngine? = null
    private var unloadJob: Job? = null

    @Volatile var busy: Boolean = false
        private set

    @Volatile var lastSummary: String = "未使用"
        private set

    fun isBusy(): Boolean = busy

    fun isModelLoaded(): Boolean = engine?.state?.value is LlamaState.ModelReady

    /** 立刻释放模型（用户手动释放，或空闲到点）。 */
    suspend fun unload() {
        val e = engine ?: return
        try {
            if (e.state.value is LlamaState.ModelReady) e.unloadModel()
            lastSummary = "已释放模型"
        } catch (t: Throwable) {
            Log.w(TAG, "unload failed", t)
            lastSummary = "释放失败：${t.message}"
        }
    }

    /** 释放并彻底放弃引擎实例（进程内换模型时用）。 */
    suspend fun destroy() {
        unloadJob?.cancel()
        unloadJob = null
        try {
            engine?.destroy()
        } catch (t: Throwable) {
            Log.w(TAG, "destroy failed", t)
        }
        engine = null
        lastSummary = "已销毁引擎"
    }

    /**
     * 分析一张屏幕截图。
     * 拿不到锁时立刻返回 `ok=false, error="busy"`，调用方自行决定是否重试。
     */
    suspend fun analyze(
        ctx: Context,
        jpeg: ByteArray,
        slice: Int = AdaptiveSlice.USER,
        prompt: String = DEFAULT_PROMPT,
    ): VlmResult {
        val s = AdaptiveSlice.clamp(slice)
        if (!gate.tryLock()) {
            return VlmResult(false, s, 0L, 0L, 0L, "", "busy")
        }
        busy = true
        unloadJob?.cancel()
        unloadJob = null

        var loadMs = 0L
        var prefillMs = 0L
        var genMs = 0L
        return try {
            loadMs = ensureLoaded(ctx)
            val e = engine ?: throw IllegalStateException("引擎未就绪")

            e.setImageMaxSliceNums(s)
            e.setEnableThinking(false)

            Log.i(TAG, "prefill begin slice=$s bytes=${jpeg.size}")
            val t1 = System.currentTimeMillis()
            e.prefillImage(jpeg)
            prefillMs = System.currentTimeMillis() - t1

            val sb = StringBuilder()
            val t2 = System.currentTimeMillis()
            e.sendUserPrompt(INSTRUCTION + prompt).collect { token -> sb.append(token) }
            genMs = System.currentTimeMillis() - t2
            val answer = sb.toString().trim()

            resetContext(e)

            lastSummary = "ok slice=$s 预填充=${prefillMs}ms 生成=${genMs}ms"
            Log.i(TAG, "$lastSummary answer=${answer.take(80)}")
            VlmResult(true, s, loadMs, prefillMs, genMs, answer)
        } catch (t: Throwable) {
            Log.e(TAG, "analyze failed", t)
            lastSummary = "失败：${t.message}"
            VlmResult(false, s, loadMs, prefillMs, genMs, "", t.message ?: t.toString())
        } finally {
            busy = false
            gate.unlock()
            scheduleIdleUnload()
        }
    }

    /** 每次分析都独立成段，避免上下文膨胀拖慢后续分析。 */
    private suspend fun resetContext(e: LlamaEngine) {
        try {
            // 只清上下文：setSystemPrompt 会走原生模板路径直接崩进程（见 INSTRUCTION 注释）。
            e.clearContext()
        } catch (t: Throwable) {
            Log.w(TAG, "context reset failed", t)
        }
    }

    private suspend fun ensureLoaded(ctx: Context): Long {
        val t0 = System.currentTimeMillis()
        val e = engine ?: LlamaEngine.getInstance(ctx).also { engine = it }

        if (e.state.value !is LlamaState.ModelReady) {
            var waited = 0L
            while (e.state.value !is LlamaState.Initialized) {
                val st = e.state.value
                if (st is LlamaState.Error) {
                    throw IllegalStateException("native 初始化失败：${st.exception.message}")
                }
                if (waited >= INIT_TIMEOUT_MS) {
                    throw IllegalStateException("native 初始化超时：${st.javaClass.simpleName}")
                }
                delay(100L)
                waited += 100L
            }
            val gguf = VlmPaths.gguf(ctx)
                ?: throw IllegalStateException("主模型文件缺失：${VlmPaths.internalDir(ctx)}")
            val mmproj = VlmPaths.mmproj(ctx)
                ?: throw IllegalStateException("视觉投影文件缺失：${VlmPaths.internalDir(ctx)}")

            Log.i(TAG, "loading model ${gguf.absolutePath}")
            e.loadModel(gguf.absolutePath, mmproj.absolutePath)
            // 不调用 setSystemPrompt()：视觉路径下原生实现会 SIGABRT（见 INSTRUCTION 注释）。
            Log.i(TAG, "model ready (${System.currentTimeMillis() - t0} ms)")
        }
        return System.currentTimeMillis() - t0
    }

    private fun scheduleIdleUnload() {
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(IDLE_UNLOAD_MS)
            if (!busy && isModelLoaded()) {
                Log.i(TAG, "idle ${IDLE_UNLOAD_MS / 1000}s → releasing model")
                unload()
            }
        }
    }
}

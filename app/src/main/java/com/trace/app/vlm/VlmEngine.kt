package com.trace.app.vlm

import android.content.Context
import android.util.Log
import com.example.minicpm_v_demo.LlamaEngine
import com.example.minicpm_v_demo.LlamaState
import com.example.minicpm_v_demo.ModelInfo
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

    /** 聊天单轮生成长度上限（token）。本地 2.4 GB 视觉模型，给太长会等很久。 */
    private const val CHAT_MAX_TOKENS = 320

    /**
     * 聊天页的「系统指令」。
     *
     * 同样不能走原生 `processSystemPrompt()`（见 [INSTRUCTION] 的崩溃注释），
     * 由调用方拼进用户提示词最前面。这里只写角色与纪律，数据由 ChatActivity 拼。
     */
    private const val CHAT_INSTRUCTION =
        "你是「Trace」这个 Android App 内置的助手。Trace 在本机记录用户的应用使用行为和消费流水，" +
            "下面的数据都是真实的。请只依据这些数据用中文回答用户的问题：" +
            "说人话、给出具体数字和时间、不要编造；数据不够就直说不知道。" +
            "回答控制在 200 字以内，不要用 Markdown 标题。\n\n"

    /** 等待 native 库异步加载完成的上限。 */
    private const val INIT_TIMEOUT_MS = 20_000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gate = Mutex()

    @Volatile private var engine: LlamaEngine? = null
    private var unloadJob: Job? = null

    /**
     * 当前 native 引擎里装的是哪颗脑子（模型 id）。
     *
     * 一个 `LlamaEngine` 只能装一份权重，所以「屏幕理解用 VLM、写总结用文本模型」
     * 是靠**换模型**实现的：先 `unloadModel()` 再 `loadModel(另一份 gguf)`。
     * 实测冷加载 ~1.3 s（视觉模型），代价可以接受，没必要两个模型同时占内存。
     */
    @Volatile private var loadedId: String? = null

    @Volatile var busy: Boolean = false
        private set

    @Volatile var lastSummary: String = "未使用"
        private set

    fun isBusy(): Boolean = busy

    fun isModelLoaded(): Boolean = engine?.state?.value is LlamaState.ModelReady

    /**
     * 写总结 / 聊天实际会用哪颗脑子。
     *
     * 优先用户选的纯文本模型（MiniCPM5-2B / Qwen3-4B …）；**没下载就回落到视觉模型**
     * ——视觉模型也能写字，只是写得差，总比整个功能不可用好。屏幕理解不受影响，
     * 它固定用 [VlmPaths.MODEL_ID]。
     */
    fun chatModel(ctx: Context): ModelInfo {
        val picked = TextModel.selected(ctx)
        if (TextModel.ready(ctx, picked)) return picked
        return TextModel.byId(TextModel.VISION_ID) ?: picked
    }

    /** 归档/界面要显示「用哪个模型写的」。 */
    fun chatModelName(ctx: Context): String = chatModel(ctx).displayName

    /** 立刻释放模型（用户手动释放，或空闲到点）。 */
    suspend fun unload() {
        val e = engine ?: return
        try {
            if (e.state.value is LlamaState.ModelReady) e.unloadModel()
            loadedId = null
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
        loadedId = null
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
            loadMs = ensureLoaded(ctx, TextModel.VISION_ID)
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

    /**
     * 纯文本对话：聊天页的**端侧大脑**。
     *
     * 与 [analyze] 的唯一区别是**不喂图**——只把拼好的提示词交给模型的语言主干。
     * 视觉与文本共用同一个 native `processUserPrompt()`，所以「不调 prefillImage」就是纯文本推理。
     * 聊天同样不能用 `setSystemPrompt()`（视觉路径下 SIGABRT，见 [INSTRUCTION] 注释），
     * 因此系统指令拼进用户提示词，并把对话历史一并铺成纯文本。
     *
     * 慢是真的慢（首次要加载 2.4 GB 权重），所以：
     * - 开跑前先 `clearContext()`，把上一轮视觉分析残留的图像上下文丢掉；
     * - 限制生成长度（默认 320 token），避免用户对着气泡等一分多钟；
     * - 通过 [onToken] 逐块回调，UI 可以边生成边显示。
     */
    suspend fun ask(
        ctx: Context,
        prompt: String,
        maxTokens: Int = CHAT_MAX_TOKENS,
        instruction: String = CHAT_INSTRUCTION,
        onToken: (String) -> Unit = {},
    ): VlmResult {
        val s = AdaptiveSlice.USER
        if (!gate.tryLock()) {
            return VlmResult(false, s, 0L, 0L, 0L, "", "busy")
        }
        busy = true
        unloadJob?.cancel()
        unloadJob = null

        var loadMs = 0L
        var genMs = 0L
        val chat = chatModel(ctx)
        return try {
            loadMs = ensureLoaded(ctx, chat.id)
            val e = engine ?: throw IllegalStateException("引擎未就绪")
            e.setEnableThinking(false)
            e.clearContext()

            val sb = StringBuilder()
            val t2 = System.currentTimeMillis()
            e.sendUserPrompt(instruction + prompt, maxTokens).collect { tok ->
                if (tok.isNotEmpty()) {
                    sb.append(tok)
                    onToken(tok)
                }
            }
            genMs = System.currentTimeMillis() - t2
            // Qwen3 这类模型会先吐  thinking…<｜end▁of▁thinking｜>，裁掉再交给调用方。
            val answer = stripThinking(sb.toString())

            resetContext(e)
            lastSummary = "chat 生成=${genMs}ms 长度=${answer.length} 模型=${chat.id}"
            Log.i(TAG, "$lastSummary answer=${answer.take(80)}")
            VlmResult(true, s, loadMs, 0L, genMs, answer)
        } catch (t: Throwable) {
            Log.e(TAG, "ask failed", t)
            lastSummary = "失败：${t.message}"
            VlmResult(false, s, loadMs, 0L, genMs, "", t.message ?: t.toString())
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

    /**
     * 确保 native 引擎里装的是 [modelId] 这颗脑子，返回（含换模型在内的）加载耗时。
     *
     * 换模型 = 先卸后装：一个 [LlamaEngine] 只能持有一份权重。视觉模型要
     * mmproj，纯文本模型传 null（`loadModel` 的 mmproj 参数本来就可空）。
     */
    private suspend fun ensureLoaded(ctx: Context, modelId: String): Long {
        val t0 = System.currentTimeMillis()
        var e = engine ?: LlamaEngine.getInstance(ctx).also { engine = it }

        if (loadedId != null && loadedId != modelId && e.state.value is LlamaState.ModelReady) {
            Log.i(TAG, "换模型：$loadedId → $modelId")
            var ok = true
            try {
                e.unloadModel()
            } catch (t: Throwable) {
                ok = false
                Log.w(TAG, "unloadModel 失败，改为重建引擎", t)
            }
            loadedId = null
            if (!ok) {
                destroy()
                e = engine ?: LlamaEngine.getInstance(ctx).also { engine = it }
            }
        }

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

            val ggufPath: String
            val mmprojPath: String?
            if (modelId == TextModel.VISION_ID) {
                ggufPath = VlmPaths.gguf(ctx)?.absolutePath
                    ?: throw IllegalStateException("主模型文件缺失：${VlmPaths.internalDir(ctx)}")
                mmprojPath = VlmPaths.mmproj(ctx)?.absolutePath
                    ?: throw IllegalStateException("视觉投影文件缺失：${VlmPaths.internalDir(ctx)}")
            } else {
                val m = TextModel.byId(modelId)
                    ?: throw IllegalStateException("未知模型：$modelId")
                val f = TextModel.find(ctx, m)
                    ?: throw IllegalStateException("文字模型未下载：${m.displayName}")
                ggufPath = f.absolutePath
                mmprojPath = null
            }

            Log.i(TAG, "loading model ($modelId) $ggufPath")
            e.loadModel(ggufPath, mmprojPath)
            loadedId = modelId
            // 不调用 setSystemPrompt()：视觉路径下原生实现会 SIGABRT（见 INSTRUCTION 注释）。
            Log.i(TAG, "model ready ($modelId, ${System.currentTimeMillis() - t0} ms)")
        }
        return System.currentTimeMillis() - t0
    }

    /**
     * 裁掉思考模型（Qwen3 等）的 ` thinking…` 段。
     *
     * 原生侧没有 chat template 开关，`setEnableThinking(false)` 对小众模型不一定生效，
     * 所以这里兜一层：成对出现的整段删掉；**只开不闭**（token 用光还在想）则整条丢弃，
     * 因为剩下的半截思考过程不是答案。
     */
    private fun stripThinking(raw: String): String {
        val s = raw.trim()
        val open = s.indexOf(" thinking")
        val close = s.indexOf("<｜end▁of▁thinking｜>")
        return when {
            open < 0 && close < 0 -> s
            open >= 0 && close > open -> (s.substring(0, open) + s.substring(close + 8)).trim()
            open >= 0 && close < 0 -> s.substring(0, open).trim().ifBlank { "" }
            else -> s.substring(close + 8).trim()
        }
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

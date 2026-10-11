package com.trace.app.vlm

import android.content.Context
import android.util.Log
import com.example.minicpm_v_demo.ModelInfo
import java.io.File

/**
 * Trace 的「文字模型」——总结与聊天用它，屏幕理解仍走 [VlmPaths] 的视觉模型。
 *
 * **为什么要把两颗脑子分开**：本机跑的是 MiniCPM-V 4.6，gguf 元数据里
 * `general.size_label = 752M`，视觉投影（mmproj，1.1 GB）比语言骨干（0.5 GB）
 * 还大——看图够用，写中文总结明显不行：数字会抄错、会照抄提示词里的示例数字、
 * 会漏掉喂进去的要点。
 *
 * 官方 demo 的 [ModelInfo.AVAILABLE_MODELS] 里**本来就注册了纯文本模型**，
 * 且 `LlamaEngine.loadModel(path, pathToMmproj = null)` 是设计内路径
 * （mmproj 参数可空，加载失败也只 warn 一句继续跑），所以这里只是把
 * 「另一颗脑子」接上，不需要动 native。
 *
 * 文件布局与 demo 一致：`{filesDir}/models/{id}/{ggufFileName}`；
 * 另外支持外部私有目录（adb push 调试方便），搜索顺序内部 → 外部。
 */
object TextModel {

    private const val TAG = "TraceText"
    private const val PREF = "trace_prefs"
    private const val KEY = "trace_text_model"

    /** 视觉模型 id，用来判断引擎里当前装的是哪颗脑子。 */
    const val VISION_ID = VlmPaths.MODEL_ID

    /**
     * 候选顺序 = 设置页展示顺序，**第一个就是默认**（没选过时 [selected] 回落到它）。
     * 2026-10-11 用户定：默认挂 Qwen3-4B（写得更像回事，代价是慢 5-7 倍，设置里可随时切回 2B）。
     * 同日追加 Qwen3-8B：用户要求「先保证质量，速度慢点没事」→ 给一个更大的备选，
     * 总结是熄屏后台写的，单次 4-5 分钟可以接受。
     */
    private val ORDER = listOf("qwen3-4b", "qwen3-8b", "minicpm5-2b", "minicpm5-0.9b")

    /**
     * 已知的文件大小（字节），用于「下载前先看代价」。
     * 来自 2026-10-11 对 HuggingFace / ModelScope 的 HEAD 实测；没测过的留空，
     * 界面就显示「未知大小」而不是编一个数字出来。
     */
    private val HINT_BYTES = mapOf(
        "minicpm5-2b" to 1_561_318_368L,
        "qwen3-4b" to 2_497_280_256L,
    )

    val candidates: List<ModelInfo> = ORDER.mapNotNull { id ->
        ModelInfo.AVAILABLE_MODELS.firstOrNull { it.id == id }
    }

    fun byId(id: String): ModelInfo? = ModelInfo.AVAILABLE_MODELS.firstOrNull { it.id == id }

    /** 当前选中的文字模型；没选过、或选中的已不在表里 → 回落到第一个候选。 */
    fun selected(ctx: Context): ModelInfo {
        val id = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)
        return byId(id ?: "")?.takeIf { it.isTextOnly } ?: candidates.first()
    }

    fun select(ctx: Context, id: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, id).apply()
        Log.i(TAG, "selected text model → $id")
    }

    fun internalFile(ctx: Context, m: ModelInfo): File =
        File(File(File(ctx.filesDir, "models"), m.id), m.ggufFileName)

    fun externalFile(ctx: Context, m: ModelInfo): File =
        File(File(File(ctx.getExternalFilesDir(null), "models"), m.id), m.ggufFileName)

    /** 找到可用的 gguf（内部优先、其次外部）；都没有返回 null。 */
    fun find(ctx: Context, m: ModelInfo): File? {
        for (f in listOf(internalFile(ctx, m), externalFile(ctx, m))) {
            if (f.isFile && f.length() > 0L) return f
        }
        return null
    }

    fun ready(ctx: Context, m: ModelInfo): Boolean = find(ctx, m) != null

    /** 「1.46 GB」/「未知大小」——只用实测过或磁盘上真实存在的数字。 */
    fun sizeText(ctx: Context, m: ModelInfo): String {
        val bytes = find(ctx, m)?.length() ?: HINT_BYTES[m.id] ?: return "未知大小"
        val gb = bytes / 1024.0 / 1024.0 / 1024.0
        return if (gb >= 1.0) "%.2f GB".format(gb) else "%.0f MB".format(bytes / 1024.0 / 1024.0)
    }

    /** 给界面用的一句话状态。 */
    fun stateText(ctx: Context, m: ModelInfo): String =
        if (ready(ctx, m)) "已就绪 · " + sizeText(ctx, m) else "未下载 · " + sizeText(ctx, m)
}

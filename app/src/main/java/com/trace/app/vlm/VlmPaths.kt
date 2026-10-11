package com.trace.app.vlm

import android.content.Context
import java.io.File

/**
 * 端侧 VLM 的模型文件位置与身份信息。
 *
 * 主模型 + 视觉投影（mmproj）来自官方 MiniCPM-V 4.6 GGUF；
 * 搜索顺序：内部私有目录（mmap 友好）→ 外部私有目录（adb push 方便）。
 */
object VlmPaths {

    const val MODEL_ID = "minicpm-v-4_6-instruct"
    const val GGUF_NAME = "MiniCPM-V-4_6-Q4_K_M.gguf"
    const val MMPROJ_NAME = "mmproj-model-f16.gguf"

    // 与官方 v2.5 ModelInfo.kt 一致（真机已校验）
    const val GGUF_MD5 = "fd778481dd56b6036dd8f9cf7c1519cf"
    const val MMPROJ_MD5 = "54aea6e04d752f47309a48f12795a1a3"
    const val GGUF_BYTES = 529101504L
    const val MMPROJ_BYTES = 1108746944L

    fun internalDir(ctx: Context): File = File(File(ctx.filesDir, "models"), MODEL_ID)

    fun externalDir(ctx: Context): File =
        File(File(ctx.getExternalFilesDir(null), "models"), MODEL_ID)

    private fun pick(ctx: Context, name: String): File? {
        for (dir in listOf(internalDir(ctx), externalDir(ctx))) {
            val f = File(dir, name)
            if (f.isFile && f.length() > 0L) return f
        }
        return null
    }

    fun gguf(ctx: Context): File? = pick(ctx, GGUF_NAME)

    fun mmproj(ctx: Context): File? = pick(ctx, MMPROJ_NAME)

    fun ready(ctx: Context): Boolean = gguf(ctx) != null && mmproj(ctx) != null

    /** 人类可读的模型状态，用于界面提示。 */
    fun describe(ctx: Context): String {
        fun line(label: String, f: File?): String =
            if (f == null) "$label：缺失" else "$label：${f.length() / 1024 / 1024} MB · ${f.parent}"
        return line("主模型", gguf(ctx)) + "\n" + line("视觉投影", mmproj(ctx))
    }
}

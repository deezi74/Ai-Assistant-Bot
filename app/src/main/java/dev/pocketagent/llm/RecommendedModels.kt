package dev.pocketagent.llm

import dev.pocketagent.core.llm.HfFile
import dev.pocketagent.core.llm.HfReference

/**
 * Hand-picked GGUF builds (Q4_K_M) that run on phones with llama.cpp and follow
 * TruAgent's tool-call format. Sizes are the exact file sizes on Hugging Face.
 */
data class RecommendedModel(
    val id: String,
    val name: String,
    val repo: String,
    val file: String,
    val sizeBytes: Long,
    val minRamGb: Int,
    val tag: String,
    val blurb: String
) {
    val reference: HfReference get() = HfReference(repo = repo, file = file)
    val hfFile: HfFile get() = HfFile(path = file, sizeBytes = sizeBytes)
}

object RecommendedModels {
    val all = listOf(
        RecommendedModel(
            "qwen3-0.6b", "Qwen3 0.6B", "bartowski/Qwen_Qwen3-0.6B-GGUF", "Qwen_Qwen3-0.6B-Q4_K_M.gguf",
            484_220_320L, 3, "Fastest",
            "Runs on any phone and answers almost instantly. Handles simple tool calls like the flashlight or a timer."
        ),
        RecommendedModel(
            "qwen3-1.7b", "Qwen3 1.7B", "bartowski/Qwen_Qwen3-1.7B-GGUF", "Qwen_Qwen3-1.7B-Q4_K_M.gguf",
            1_282_439_584L, 4, "Best balance",
            "The best pick for most phones. Reliable with tools, web search and phone controls, and thinks before it answers."
        ),
        RecommendedModel(
            "gemma3-1b", "Gemma 3 1B", "bartowski/google_gemma-3-1b-it-GGUF", "google_gemma-3-1b-it-Q4_K_M.gguf",
            806_058_496L, 4, "Light writer",
            "Quick and friendly for chatting and writing. Less dependable with multi-step tool use."
        ),
        RecommendedModel(
            "qwen2.5-3b", "Qwen2.5 3B Instruct", "bartowski/Qwen2.5-3B-Instruct-GGUF", "Qwen2.5-3B-Instruct-Q4_K_M.gguf",
            1_929_903_264L, 6, "Snappy agent",
            "Strong at tool calling with no thinking pause, so replies start sooner. Good on 6 GB phones."
        ),
        RecommendedModel(
            "qwen3-4b", "Qwen3 4B", "bartowski/Qwen_Qwen3-4B-GGUF", "Qwen_Qwen3-4B-Q4_K_M.gguf",
            2_497_280_960L, 8, "Smartest for 8 GB",
            "Noticeably smarter research and planning. Slower, so best on phones with 8 GB of RAM or more."
        ),
        RecommendedModel(
            "gemma3-4b", "Gemma 3 4B", "bartowski/google_gemma-3-4b-it-GGUF", "google_gemma-3-4b-it-Q4_K_M.gguf",
            2_489_758_112L, 8, "Best writing",
            "Great general knowledge and natural writing. Tool use is decent; Qwen3 4B is better at it."
        ),
        RecommendedModel(
            "qwen3-8b", "Qwen3 8B", "bartowski/Qwen_Qwen3-8B-GGUF", "Qwen_Qwen3-8B-Q4_K_M.gguf",
            5_027_784_224L, 12, "Flagship only",
            "Closest to a cloud model. Needs 12 GB of RAM or more and patience; expect a few words per second."
        )
    )

    fun byFile(fileName: String): RecommendedModel? = all.firstOrNull { it.file == fileName }

    /** The model TruAgent suggests for a phone with [totalRamBytes] of memory. */
    fun bestFor(totalRamBytes: Long): RecommendedModel {
        val gb = totalRamBytes / 1_000_000_000.0
        val id = when {
            gb >= 7.5 -> "qwen3-4b"
            gb >= 3.6 -> "qwen3-1.7b"
            else -> "qwen3-0.6b"
        }
        return all.first { it.id == id }
    }

    fun fits(model: RecommendedModel, totalRamBytes: Long): Boolean = totalRamBytes / 1_000_000_000.0 >= model.minRamGb - 0.5
}

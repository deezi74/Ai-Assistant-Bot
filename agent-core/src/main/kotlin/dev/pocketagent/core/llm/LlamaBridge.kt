package dev.pocketagent.core.llm

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin JNI surface over libagentnative (llama.cpp). Every call that touches a
 * session must come from the same thread; [dev.pocketagent.llm.LocalGgufEngine]
 * guarantees that with a dedicated single-thread dispatcher.
 */
object LlamaBridge {
    const val LIBRARY_NAME = "agentnative"

    const val ERR_TOKENIZE = -1
    const val ERR_CONTEXT_FULL = -2
    const val ERR_DECODE = -3
    const val ERR_ABORTED = -4
    const val ERR_BAD_SESSION = -5

    private val loaded = AtomicBoolean(false)

    @Volatile
    var systemInfo: String = ""
        private set

    /**
     * Loads the native library and initialises the llama.cpp backend once.
     * Pass [absolutePath] on desktop JVMs; on Android leave it null so the
     * library is resolved from the APK's native lib directory.
     */
    @Synchronized
    fun ensureLoaded(absolutePath: String? = null) {
        if (loaded.get()) return
        if (absolutePath.isNullOrBlank()) {
            System.loadLibrary(LIBRARY_NAME)
        } else {
            System.load(absolutePath)
        }
        systemInfo = nativeInit()
        loaded.set(true)
    }

    val isLoaded: Boolean get() = loaded.get()

    fun describeError(code: Int): String = when (code) {
        ERR_TOKENIZE -> "Prompt could not be tokenized"
        ERR_CONTEXT_FULL -> "Prompt does not fit in the context window"
        ERR_DECODE -> "llama_decode failed while reading the prompt"
        ERR_ABORTED -> "Generation aborted"
        ERR_BAD_SESSION -> "Native session is not initialised"
        else -> "Native error $code"
    }

    @JvmStatic external fun nativeInit(): String

    @JvmStatic external fun nativeLoadModel(path: String, nGpuLayers: Int, useMmap: Boolean, useMlock: Boolean): Long

    @JvmStatic external fun nativeFreeModel(model: Long)

    /** JSON object: description, parameters, sizeBytes, layers, contextTrain, vocab, hasChatTemplate. */
    @JvmStatic external fun nativeModelInfo(model: Long): String

    @JvmStatic external fun nativeNewSession(model: Long, nCtx: Int, nBatch: Int, nThreads: Int): Long

    @JvmStatic external fun nativeFreeSession(session: Long)

    @JvmStatic external fun nativeContextSize(session: Long): Int

    /** Formats a conversation with the model's embedded chat template (ChatML fallback). */
    @JvmStatic external fun nativeApplyChatTemplate(
        model: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean
    ): String?

    @JvmStatic external fun nativeCountTokens(model: Long, text: String): Int

    /** Evaluates the prompt (reusing the cached prefix). Returns prompt token count or a negative error code. */
    @JvmStatic external fun nativeStartCompletion(
        session: Long,
        prompt: String,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        repeatLastN: Int,
        seed: Int,
        maxNewTokens: Int
    ): Int

    /** Next UTF-8 chunk; empty when a code point straddles tokens; null at end of generation. */
    @JvmStatic external fun nativeNextToken(session: Long): ByteArray?

    /** Thread-safe: may be called from any thread to interrupt prompt evaluation or sampling. */
    @JvmStatic external fun nativeAbort(session: Long)

    @JvmStatic external fun nativeResetSession(session: Long)
}

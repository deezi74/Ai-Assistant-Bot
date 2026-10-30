package dev.pocketagent.llm

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import dev.pocketagent.core.llm.ChatMessage
import dev.pocketagent.core.llm.FinishReason
import dev.pocketagent.core.llm.GenerationParams
import dev.pocketagent.core.llm.GgufMetadata
import dev.pocketagent.core.llm.GgufMetadataReader
import dev.pocketagent.core.llm.LLMProvider
import dev.pocketagent.core.llm.LlamaBridge
import dev.pocketagent.core.llm.LlmEvent
import dev.pocketagent.core.llm.LlmException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

sealed interface LocalModelState {
    data object Idle : LocalModelState
    data class Loading(val fileName: String) : LocalModelState
    data class Ready(
        val fileName: String,
        val description: String,
        val contextSize: Int,
        val sizeBytes: Long,
        val estimatedRamBytes: Long
    ) : LocalModelState
    data class Error(val message: String) : LocalModelState
}

data class MemoryReport(val availableBytes: Long, val totalBytes: Long, val lowMemory: Boolean, val thresholdBytes: Long)

/**
 * On-device llama.cpp backend. All native calls run on one dedicated thread
 * because a llama_context is not thread-safe; [abort] is the only call allowed
 * from elsewhere. Before loading, the GGUF header is parsed to estimate weights +
 * KV cache + scratch, and the context window is shrunk until it fits in RAM.
 */
class LocalGgufEngine(private val context: Context) : LLMProvider {

    private val nativeThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "llama-native").apply { priority = Thread.MAX_PRIORITY - 1 }
    }
    private val dispatcher = nativeThread.asCoroutineDispatcher()
    private val mutex = Mutex()

    private var modelHandle = 0L
    @Volatile
    private var sessionHandle = 0L
    private var contextSize = 0
    private var loadedPath: String? = null

    private val _state = MutableStateFlow<LocalModelState>(LocalModelState.Idle)
    val state: StateFlow<LocalModelState> = _state.asStateFlow()

    override val id: String = "local"
    override val displayName: String
        get() = (state.value as? LocalModelState.Ready)?.fileName?.let { "Local · $it" } ?: "Local"
    override val supportsVision: Boolean = false

    override suspend fun isReady(): Boolean = sessionHandle != 0L

    val currentModelPath: String? get() = loadedPath

    fun memoryReport(): MemoryReport {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return MemoryReport(info.availMem, info.totalMem, info.lowMemory, info.threshold)
    }

    /**
     * Picks the largest context ≤ [requested] whose estimated footprint fits the RAM
     * budget (available memory minus the low-memory threshold and a 256 MiB margin).
     */
    fun planContext(meta: GgufMetadata, requested: Int, nBatch: Int, memory: MemoryReport): Int {
        val budget = memory.availableBytes - memory.thresholdBytes - SAFETY_MARGIN
        val trainCtx = if (meta.contextLength > 0) meta.contextLength else requested
        var ctx = minOf(requested, trainCtx).coerceAtLeast(MIN_CTX)
        while (ctx > MIN_CTX && meta.estimatedRuntimeBytes(ctx, nBatch) > budget) {
            ctx /= 2
        }
        val need = meta.estimatedRuntimeBytes(ctx, nBatch)
        if (need > budget) {
            throw LlmException(
                "Not enough RAM for ${meta.name ?: "this model"}: needs about ${gib(need)} GB " +
                    "even at $ctx tokens, only ${gib(budget.coerceAtLeast(0))} GB is free. " +
                    "Close other apps or pick a smaller quantization (Q4_K_M / Q3_K_M, ≤3B params)."
            )
        }
        return ctx
    }

    suspend fun load(path: String, requestedContext: Int, threads: Int): LocalModelState = mutex.withLock {
        withContext(dispatcher) {
            val file = File(path)
            _state.value = LocalModelState.Loading(file.name)
            try {
                if (!file.isFile) throw LlmException("Model file not found: $path")
                LlamaBridge.ensureLoaded()
                val meta = GgufMetadataReader.read(file)
                val memory = memoryReport()
                val ctx = planContext(meta, requestedContext, N_BATCH, memory)

                releaseNative()
                val model = LlamaBridge.nativeLoadModel(file.absolutePath, 0, true, false)
                if (model == 0L) throw LlmException("llama.cpp could not load ${file.name} (unsupported architecture or corrupt file)")
                val session = LlamaBridge.nativeNewSession(model, ctx, N_BATCH, threads)
                if (session == 0L) {
                    LlamaBridge.nativeFreeModel(model)
                    throw LlmException("Could not allocate a $ctx-token context")
                }
                modelHandle = model
                sessionHandle = session
                contextSize = LlamaBridge.nativeContextSize(session)
                loadedPath = file.absolutePath

                val description = LlamaBridge.nativeModelInfo(model)
                    .substringAfter("\"description\":\"", "")
                    .substringBefore('"')
                    .ifBlank { meta.architecture }
                Log.i(TAG, "Loaded ${file.name}: ctx=$contextSize threads=$threads ${LlamaBridge.systemInfo}")
                LocalModelState.Ready(
                    fileName = file.name,
                    description = description,
                    contextSize = contextSize,
                    sizeBytes = file.length(),
                    estimatedRamBytes = meta.estimatedRuntimeBytes(contextSize, N_BATCH)
                ).also { _state.value = it }
            } catch (e: Throwable) {
                Log.e(TAG, "Model load failed", e)
                val message = when (e) {
                    is UnsatisfiedLinkError -> "Native library missing for this CPU (arm64-v8a required): ${e.message}"
                    else -> e.message ?: e.javaClass.simpleName
                }
                LocalModelState.Error(message).also { _state.value = it }
            }
        }
    }

    suspend fun unload() = mutex.withLock {
        withContext(dispatcher) {
            releaseNative()
            _state.value = LocalModelState.Idle
        }
    }

    /** Interrupts prompt evaluation or sampling from any thread. */
    fun abort() {
        val session = sessionHandle
        if (session != 0L) LlamaBridge.nativeAbort(session)
    }

    override fun stream(messages: List<ChatMessage>, params: GenerationParams): Flow<LlmEvent> = flow {
        mutex.withLock {
            val session = sessionHandle
            val model = modelHandle
            if (session == 0L || model == 0L) {
                emit(LlmEvent.Failure("No local model loaded"))
                emit(LlmEvent.Done(FinishReason.ERROR, 0))
                return@withLock
            }

            val reserve = params.maxTokens.coerceIn(64, contextSize / 2)
            val prompt = buildPrompt(model, messages, contextSize - reserve)
            if (prompt == null) {
                emit(LlmEvent.Failure("Conversation is too long for the ${contextSize}-token context. Start a new chat or raise the context size."))
                emit(LlmEvent.Done(FinishReason.ERROR, 0))
                return@withLock
            }

            val promptTokens = LlamaBridge.nativeCountTokens(model, prompt)
            val maxNew = minOf(params.maxTokens, contextSize - promptTokens - 4).coerceAtLeast(1)
            val rc = LlamaBridge.nativeStartCompletion(
                session, prompt,
                params.temperature, params.topP, params.topK, params.minP,
                params.repeatPenalty, params.repeatLastN, params.seed, maxNew
            )
            if (rc < 0) {
                if (rc == LlamaBridge.ERR_ABORTED) {
                    emit(LlmEvent.Done(FinishReason.CANCELLED, 0))
                } else {
                    emit(LlmEvent.Failure(LlamaBridge.describeError(rc)))
                    emit(LlmEvent.Done(FinishReason.ERROR, 0))
                }
                return@withLock
            }
            emit(LlmEvent.PromptProcessed(rc))

            var generated = 0
            var reason = FinishReason.STOP
            while (true) {
                currentCoroutineContext().ensureActive()
                if (generated >= maxNew) {
                    reason = FinishReason.LENGTH
                    break
                }
                val bytes = LlamaBridge.nativeNextToken(session) ?: break
                generated++
                if (bytes.isNotEmpty()) emit(LlmEvent.Token(String(bytes, Charsets.UTF_8)))
            }
            emit(LlmEvent.Done(reason, generated))
        }
    }.flowOn(dispatcher)

    /**
     * Formats with the model's chat template, dropping the oldest non-system turns
     * until the prompt fits in [budgetTokens]. Images are replaced by a marker.
     */
    private fun buildPrompt(model: Long, messages: List<ChatMessage>, budgetTokens: Int): String? {
        val working = messages.map { m ->
            if (m.imageJpegBase64 != null) m.copy(content = m.content + "\n[image omitted: local model has no vision input]", imageJpegBase64 = null) else m
        }.toMutableList()
        while (true) {
            val roles = working.map { it.role.wireName }.toTypedArray()
            val contents = working.map { it.content }.toTypedArray()
            val prompt = LlamaBridge.nativeApplyChatTemplate(model, roles, contents, true) ?: return null
            val tokens = LlamaBridge.nativeCountTokens(model, prompt)
            if (tokens in 0..budgetTokens) return prompt
            val firstDroppable = working.indexOfFirst { it.role != dev.pocketagent.core.llm.ChatRole.SYSTEM }
            // Keep at least the system prompt and the latest turn.
            if (firstDroppable < 0 || firstDroppable >= working.size - 1) return null
            working.removeAt(firstDroppable)
        }
    }

    private fun releaseNative() {
        if (sessionHandle != 0L) {
            LlamaBridge.nativeFreeSession(sessionHandle)
            sessionHandle = 0L
        }
        if (modelHandle != 0L) {
            LlamaBridge.nativeFreeModel(modelHandle)
            modelHandle = 0L
        }
        contextSize = 0
        loadedPath = null
    }

    override suspend fun release() = unload()

    companion object {
        private const val TAG = "LocalGgufEngine"
        const val N_BATCH = 512
        private const val MIN_CTX = 512
        private const val SAFETY_MARGIN = 256L * 1024 * 1024

        private fun gib(bytes: Long): String = String.format(java.util.Locale.US, "%.1f", bytes / 1_073_741_824.0)
    }
}

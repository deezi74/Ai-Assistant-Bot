package dev.pocketagent.core.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

enum class ProviderPreference { LOCAL_ONLY, REMOTE_ONLY, LOCAL_FIRST, REMOTE_FIRST }

/**
 * Chooses which backend serves each request. With a *_FIRST preference it falls
 * back to the other backend when the first is not ready, or when it fails before
 * producing a single token (so a half-finished answer is never spliced).
 */
class ProviderScheduler(
    private val local: LLMProvider?,
    private val remote: LLMProvider?,
    @Volatile var preference: ProviderPreference = ProviderPreference.LOCAL_FIRST
) : LLMProvider {

    @Volatile
    var lastUsed: LLMProvider? = null
        private set

    override val id: String = "scheduler"
    override val displayName: String
        get() = lastUsed?.displayName ?: "Auto"

    override val supportsVision: Boolean
        get() = orderedCandidates().firstOrNull()?.supportsVision ?: false

    override suspend fun isReady(): Boolean = orderedCandidates().any { it.isReady() }

    /** The provider that would serve the next request, or null when none is ready. */
    suspend fun activeProvider(): LLMProvider? = orderedCandidates().firstOrNull { it.isReady() }

    private fun orderedCandidates(): List<LLMProvider> = when (preference) {
        ProviderPreference.LOCAL_ONLY -> listOfNotNull(local)
        ProviderPreference.REMOTE_ONLY -> listOfNotNull(remote)
        ProviderPreference.LOCAL_FIRST -> listOfNotNull(local, remote)
        ProviderPreference.REMOTE_FIRST -> listOfNotNull(remote, local)
    }

    override fun stream(messages: List<ChatMessage>, params: GenerationParams): Flow<LlmEvent> = flow {
        val candidates = orderedCandidates().filter { it.isReady() }
        if (candidates.isEmpty()) {
            emit(LlmEvent.Failure("No model is ready. Load a local GGUF model or configure a remote endpoint in Settings."))
            emit(LlmEvent.Done(FinishReason.ERROR, 0))
            return@flow
        }
        var lastFailure: LlmEvent.Failure? = null
        for (provider in candidates) {
            lastUsed = provider
            var producedOutput = false
            var failedEarly = false
            provider.stream(messages, params).collect { event ->
                when (event) {
                    is LlmEvent.Token, is LlmEvent.Reasoning -> {
                        producedOutput = true
                        emit(event)
                    }
                    is LlmEvent.Failure -> {
                        if (producedOutput) emit(event) else {
                            failedEarly = true
                            lastFailure = event
                        }
                    }
                    is LlmEvent.Done -> {
                        if (!failedEarly) emit(event)
                    }
                    is LlmEvent.PromptProcessed -> emit(event)
                }
            }
            if (!failedEarly) return@flow
        }
        val failure = lastFailure ?: LlmEvent.Failure("All providers failed")
        emit(failure)
        emit(LlmEvent.Done(FinishReason.ERROR, 0))
    }

    override suspend fun release() {
        local?.release()
        remote?.release()
    }
}

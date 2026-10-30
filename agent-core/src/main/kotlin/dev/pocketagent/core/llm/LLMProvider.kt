package dev.pocketagent.core.llm

import kotlinx.coroutines.flow.Flow

/** Conversation roles shared by every backend. Observations travel as [USER] turns. */
enum class ChatRole(val wireName: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant")
}

/**
 * One turn of conversation. [imageJpegBase64] carries an optional screenshot for
 * vision-capable backends; text-only backends ignore it.
 */
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val imageJpegBase64: String? = null
)

data class GenerationParams(
    val maxTokens: Int = 1024,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val repeatLastN: Int = 64,
    val seed: Int = -1
)

enum class FinishReason { STOP, LENGTH, CANCELLED, ERROR }

/** Raw events emitted by a provider stream before agent-level parsing. */
sealed interface LlmEvent {
    /** A chunk of visible model output. */
    data class Token(val text: String) : LlmEvent

    /** Out-of-band reasoning text from APIs that separate it (OpenRouter, Anthropic thinking). */
    data class Reasoning(val text: String) : LlmEvent

    /** Prompt evaluation stats, emitted once before the first token when known. */
    data class PromptProcessed(val promptTokens: Int) : LlmEvent

    data class Done(val reason: FinishReason, val generatedTokens: Int) : LlmEvent

    data class Failure(val message: String, val cause: Throwable? = null) : LlmEvent
}

/**
 * Unified inference contract. Implementations stream tokens through a cold [Flow];
 * collecting starts generation and cancelling the collector stops it, which is how
 * the agent loop freezes output the moment a tool call closes.
 */
interface LLMProvider {
    val id: String
    val displayName: String
    val supportsVision: Boolean

    /** True when the backend can serve a request right now (model loaded / key present). */
    suspend fun isReady(): Boolean

    fun stream(messages: List<ChatMessage>, params: GenerationParams = GenerationParams()): Flow<LlmEvent>

    /** Release native or network resources. The provider may be reused after [isReady] returns true again. */
    suspend fun release()
}

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

package dev.pocketagent.core.agent

import dev.pocketagent.core.llm.ChatMessage
import dev.pocketagent.core.llm.ChatRole
import dev.pocketagent.core.llm.FinishReason
import dev.pocketagent.core.llm.GenerationParams
import dev.pocketagent.core.llm.LLMProvider
import dev.pocketagent.core.llm.LlmEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

data class AgentConfig(
    val maxSteps: Int = 12,
    val toolTimeoutMs: Long = 60_000,
    val maxObservationChars: Int = 8_000,
    val generation: GenerationParams = GenerationParams()
)

sealed interface AgentEvent {
    data class StepStarted(val step: Int, val provider: String) : AgentEvent
    data class AnswerDelta(val text: String) : AgentEvent
    data class ThoughtDelta(val text: String) : AgentEvent
    data class ToolCallStarted(val step: Int, val name: String, val argumentsJson: String) : AgentEvent
    data class Observation(
        val step: Int,
        val name: String,
        val output: String,
        val isError: Boolean,
        val hasImage: Boolean
    ) : AgentEvent
    data class Completed(val answer: String, val steps: Int) : AgentEvent
    data class Failed(val message: String) : AgentEvent
}

/**
 * Reason → Act → Observe orchestrator.
 *
 * Each step streams the model. Visible text goes out as [AgentEvent.AnswerDelta];
 * `<think>` content and API reasoning go out as [AgentEvent.ThoughtDelta]. When a
 * `</tool_call>` closes, collection is cancelled (which stops native sampling or
 * aborts the HTTP stream), the tool runs, and its raw output is appended verbatim as
 * an `[Observation]` user turn. The loop ends when a step produces no tool call.
 */
class AgentLoop(
    private val provider: LLMProvider,
    private val tools: ToolRegistry,
    private val persona: PersonaManager,
    private val config: AgentConfig = AgentConfig()
) {
    private class ToolCallReached : CancellationException("tool call reached")

    fun run(history: List<ChatMessage>, userMessage: String, mode: AgentMode): Flow<AgentEvent> = flow {
        val messages = ArrayList<ChatMessage>()
        messages += ChatMessage(ChatRole.SYSTEM, persona.buildSystemPrompt(mode, tools.manifest(mode)))
        messages += history.filter { it.role != ChatRole.SYSTEM }
        messages += ChatMessage(ChatRole.USER, userMessage)

        for (step in 1..config.maxSteps) {
            val lastStep = step == config.maxSteps
            if (lastStep && step > 1) {
                messages += ChatMessage(
                    ChatRole.USER,
                    "[System] Step budget exhausted. Give your final answer now without calling any tool."
                )
            }
            emit(AgentEvent.StepStarted(step, provider.displayName))

            val parser = ToolCallParser()
            val visible = StringBuilder()
            var toolCall: ParsedToolCall? = null
            var malformed: StreamSegment.MalformedCall? = null
            var failure: String? = null
            var finish = FinishReason.STOP

            suspend fun handle(segments: List<StreamSegment>) {
                for (segment in segments) {
                    if (toolCall != null || malformed != null) return
                    when (segment) {
                        is StreamSegment.Text -> {
                            visible.append(segment.text)
                            emit(AgentEvent.AnswerDelta(segment.text))
                        }
                        is StreamSegment.Thought -> emit(AgentEvent.ThoughtDelta(segment.text))
                        is StreamSegment.Call -> toolCall = segment.call
                        is StreamSegment.MalformedCall -> malformed = segment
                    }
                }
            }

            try {
                provider.stream(messages, config.generation).collect { event ->
                    when (event) {
                        is LlmEvent.Token -> {
                            handle(parser.feed(event.text))
                            if (toolCall != null || malformed != null) throw ToolCallReached()
                        }
                        is LlmEvent.Reasoning -> emit(AgentEvent.ThoughtDelta(event.text))
                        is LlmEvent.Failure -> failure = event.message
                        is LlmEvent.Done -> finish = event.reason
                        is LlmEvent.PromptProcessed -> Unit
                    }
                }
            } catch (stop: ToolCallReached) {
                // Generation frozen at the closing tag; fall through to tool execution.
            }
            if (toolCall == null && malformed == null) handle(parser.finish())

            val error = failure
            if (error != null && toolCall == null && malformed == null) {
                emit(AgentEvent.Failed(error))
                return@flow
            }
            if (finish == FinishReason.ERROR && toolCall == null && malformed == null && visible.isBlank()) {
                emit(AgentEvent.Failed("The model stream ended with an error."))
                return@flow
            }

            val call = toolCall
            val bad = malformed
            if (lastStep || (call == null && bad == null)) {
                emit(AgentEvent.Completed(visible.toString().trim(), step))
                return@flow
            }

            if (bad != null) {
                messages += ChatMessage(
                    ChatRole.ASSISTANT,
                    visible.toString() + ToolCallParser.CALL_OPEN + bad.raw + ToolCallParser.CALL_CLOSE
                )
                val note = "ERROR: could not parse your tool call (${bad.error}). " +
                    "Use exactly <tool_call>{\"name\": \"tool_name\", \"arguments\": {...}}</tool_call>."
                emit(AgentEvent.Observation(step, "parser", note, isError = true, hasImage = false))
                messages += ChatMessage(ChatRole.USER, "[Observation]\n$note")
                continue
            }

            requireNotNull(call)
            messages += ChatMessage(
                ChatRole.ASSISTANT,
                visible.toString() + ToolCallParser.CALL_OPEN + call.raw + ToolCallParser.CALL_CLOSE
            )
            emit(AgentEvent.ToolCallStarted(step, call.name, call.arguments.toString()))

            val result = executeTool(call, mode)
            val output = clip(result.output)
            val image = if (provider.supportsVision) result.imageJpegBase64 else null
            emit(AgentEvent.Observation(step, call.name, output, result.isError, image != null))

            val observationText = buildString {
                append("[Observation] ")
                append(call.name)
                append('\n')
                append(output)
                if (result.imageJpegBase64 != null && image == null) {
                    append("\n(An image was returned but the active model has no vision input.)")
                }
            }
            messages += ChatMessage(ChatRole.USER, observationText, image)
        }
    }

    private suspend fun executeTool(call: ParsedToolCall, mode: AgentMode): ToolResult {
        val tool = tools.get(call.name)
            ?: return ToolResult.error("unknown tool '${call.name}'. Available: ${tools.forMode(mode).joinToString { it.name }}")
        if (mode !in tool.modes) return ToolResult.error("tool '${call.name}' is not enabled in ${mode.label} mode")
        if (!tool.isAvailable()) return ToolResult.error("tool '${call.name}' is unavailable right now (service disabled or permission missing)")
        return try {
            withTimeoutOrNull(config.toolTimeoutMs) { tool.execute(call.arguments) }
                ?: ToolResult.error("tool '${call.name}' timed out after ${config.toolTimeoutMs} ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.error("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun clip(text: String): String {
        if (text.length <= config.maxObservationChars) return text
        val dropped = text.length - config.maxObservationChars
        return text.take(config.maxObservationChars) + "\n[output clipped: $dropped more characters]"
    }
}

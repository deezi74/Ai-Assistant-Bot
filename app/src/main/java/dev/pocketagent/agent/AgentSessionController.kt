package dev.pocketagent.agent

import android.content.Intent
import androidx.core.content.ContextCompat
import dev.pocketagent.AppContainer
import dev.pocketagent.core.agent.AgentConfig
import dev.pocketagent.core.agent.AgentEvent
import dev.pocketagent.core.agent.AgentLoop
import dev.pocketagent.core.agent.AgentMode
import dev.pocketagent.core.llm.ChatMessage
import dev.pocketagent.core.llm.ChatRole
import dev.pocketagent.core.llm.GenerationParams
import dev.pocketagent.data.ConversationEntity
import dev.pocketagent.data.MessageEntity
import dev.pocketagent.service.AgentForegroundService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

enum class ItemRole { USER, ASSISTANT, TOOL }

data class ChatItem(
    val id: Long,
    val role: ItemRole,
    val text: String,
    val toolName: String? = null,
    val isError: Boolean = false,
    val streaming: Boolean = false
)

enum class TerminalKind { SYSTEM, THOUGHT, ACTION, OBSERVATION, ERROR }

data class TerminalLine(val id: Long, val kind: TerminalKind, val text: String)

data class ChatUiState(
    val conversationId: Long? = null,
    val items: List<ChatItem> = emptyList(),
    val terminal: List<TerminalLine> = emptyList(),
    val running: Boolean = false,
    val step: Int = 0,
    val providerLabel: String = ""
)

/**
 * Owns the running agent session at process scope so a generation keeps going
 * while the activity is recreated or backgrounded. The UI only observes [state].
 */
class AgentSessionController(private val container: AppContainer) {
    private val dao get() = container.database.conversations()

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var job: Job? = null
    private val ids = AtomicLong(1)
    private fun newId() = ids.getAndIncrement()

    init {
        container.appScope.launch {
            val latest = dao.latestConversation() ?: return@launch
            openConversation(latest.id)
        }
    }

    suspend fun openConversation(id: Long) {
        val rows = dao.messages(id)
        _state.value = ChatUiState(
            conversationId = id,
            items = rows.map {
                ChatItem(
                    id = newId(),
                    role = when (it.role) {
                        "user" -> ItemRole.USER
                        "tool" -> ItemRole.TOOL
                        else -> ItemRole.ASSISTANT
                    },
                    text = it.content,
                    toolName = it.toolName,
                    isError = it.isError
                )
            }
        )
    }

    fun newChat() {
        stop()
        _state.value = ChatUiState()
    }

    fun clearTerminal() = _state.update { it.copy(terminal = emptyList()) }

    fun send(text: String, mode: AgentMode) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _state.value.running) return
        val settings = container.settings.current

        val history = _state.value.items.mapNotNull {
            when (it.role) {
                ItemRole.USER -> ChatMessage(ChatRole.USER, it.text)
                ItemRole.ASSISTANT -> if (it.text.isBlank()) null else ChatMessage(ChatRole.ASSISTANT, it.text)
                ItemRole.TOOL -> null
            }
        }

        val answerId = newId()
        _state.update {
            it.copy(
                items = it.items + ChatItem(newId(), ItemRole.USER, prompt) +
                    ChatItem(answerId, ItemRole.ASSISTANT, "", streaming = true),
                running = true,
                step = 0,
                terminal = it.terminal + TerminalLine(newId(), TerminalKind.SYSTEM, "▶ ${mode.label} mode · new task")
            )
        }

        startForegroundService()
        job = container.appScope.launch {
            val conversationId = ensureConversation(prompt, mode)
            dao.insertMessage(MessageEntity(conversationId = conversationId, role = "user", content = prompt, createdAt = now()))

            val loop = AgentLoop(
                provider = container.scheduler,
                tools = container.tools,
                persona = container.persona,
                config = AgentConfig(
                    maxSteps = settings.maxSteps,
                    generation = GenerationParams(maxTokens = settings.maxTokens, temperature = settings.temperature)
                )
            )
            var answer = ""
            try {
                loop.run(history, prompt, mode).collect { event -> handle(event, answerId, conversationId) { answer = it } }
            } finally {
                val finalText = answer.ifBlank { currentText(answerId) }
                if (finalText.isNotBlank()) {
                    dao.insertMessage(MessageEntity(conversationId = conversationId, role = "assistant", content = finalText, createdAt = now()))
                }
                dao.updateConversation(conversationId, titleFor(prompt), now())
                _state.update { s ->
                    s.copy(
                        running = false,
                        items = s.items.map { if (it.id == answerId) it.copy(streaming = false, text = it.text.ifBlank { finalText }) else it }
                            .filterNot { it.id == answerId && it.text.isBlank() }
                    )
                }
                stopForegroundService()
            }
        }
    }

    fun stop() {
        container.localEngine.abort()
        job?.cancel()
        job = null
    }

    private suspend fun handle(event: AgentEvent, answerId: Long, conversationId: Long, setAnswer: (String) -> Unit) {
        when (event) {
            is AgentEvent.StepStarted -> _state.update {
                it.copy(
                    step = event.step,
                    providerLabel = event.provider,
                    terminal = it.terminal + TerminalLine(newId(), TerminalKind.SYSTEM, "· step ${event.step} via ${event.provider}")
                )
            }
            is AgentEvent.AnswerDelta -> _state.update { s ->
                s.copy(items = s.items.map { if (it.id == answerId) it.copy(text = it.text + event.text) else it })
            }
            is AgentEvent.ThoughtDelta -> _state.update { s ->
                val last = s.terminal.lastOrNull()
                val terminal = if (last != null && last.kind == TerminalKind.THOUGHT) {
                    s.terminal.dropLast(1) + last.copy(text = last.text + event.text)
                } else {
                    s.terminal + TerminalLine(newId(), TerminalKind.THOUGHT, event.text.trimStart())
                }
                s.copy(terminal = terminal.takeLast(MAX_TERMINAL_LINES))
            }
            is AgentEvent.ToolCallStarted -> {
                _state.update { s ->
                    // Text streamed before the tool call was narration, not the answer: move it to the terminal.
                    val narration = s.items.firstOrNull { it.id == answerId }?.text.orEmpty().trim()
                    val terminal = s.terminal +
                        (if (narration.isNotEmpty()) listOf(TerminalLine(newId(), TerminalKind.THOUGHT, narration)) else emptyList()) +
                        TerminalLine(newId(), TerminalKind.ACTION, "→ ${event.name} ${event.argumentsJson}")
                    s.copy(
                        items = s.items.map { if (it.id == answerId) it.copy(text = "") else it },
                        terminal = terminal.takeLast(MAX_TERMINAL_LINES)
                    )
                }
            }
            is AgentEvent.Observation -> {
                dao.insertMessage(
                    MessageEntity(
                        conversationId = conversationId,
                        role = "tool",
                        content = event.output,
                        toolName = event.name,
                        isError = event.isError,
                        createdAt = now()
                    )
                )
                _state.update { s ->
                    val toolItem = ChatItem(newId(), ItemRole.TOOL, event.output, event.name, event.isError)
                    val idx = s.items.indexOfFirst { it.id == answerId }
                    val items = if (idx >= 0) s.items.toMutableList().apply { add(idx, toolItem) } else s.items + toolItem
                    s.copy(
                        items = items,
                        terminal = (s.terminal + TerminalLine(
                            newId(),
                            if (event.isError) TerminalKind.ERROR else TerminalKind.OBSERVATION,
                            "← ${event.name}: ${event.output.take(400)}"
                        )).takeLast(MAX_TERMINAL_LINES)
                    )
                }
            }
            is AgentEvent.Completed -> {
                setAnswer(event.answer)
                _state.update {
                    it.copy(terminal = it.terminal + TerminalLine(newId(), TerminalKind.SYSTEM, "✓ done in ${event.steps} step(s)"))
                }
            }
            is AgentEvent.Failed -> _state.update { s ->
                s.copy(
                    items = s.items.map {
                        if (it.id == answerId) it.copy(text = "⚠ ${event.message}", isError = true) else it
                    },
                    terminal = s.terminal + TerminalLine(newId(), TerminalKind.ERROR, "✗ ${event.message}")
                )
            }
        }
    }

    private suspend fun ensureConversation(prompt: String, mode: AgentMode): Long {
        _state.value.conversationId?.let { return it }
        val id = dao.insertConversation(
            ConversationEntity(title = titleFor(prompt), mode = mode.name, createdAt = now(), updatedAt = now())
        )
        _state.update { it.copy(conversationId = id) }
        return id
    }

    private fun currentText(id: Long) = _state.value.items.firstOrNull { it.id == id }?.text.orEmpty()

    private fun titleFor(prompt: String) = prompt.lineSequence().first().take(60)

    private fun now() = System.currentTimeMillis()

    private fun startForegroundService() {
        val ctx = container.app
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, AgentForegroundService::class.java))
        } catch (e: Exception) {
            // Starting from the background can be refused on Android 12+; generation still runs.
        }
    }

    private fun stopForegroundService() {
        val ctx = container.app
        ctx.stopService(Intent(ctx, AgentForegroundService::class.java))
    }

    companion object {
        private const val MAX_TERMINAL_LINES = 300
    }
}

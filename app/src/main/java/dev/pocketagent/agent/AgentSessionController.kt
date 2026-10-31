package dev.pocketagent.agent

import android.content.Intent
import androidx.core.content.ContextCompat
import dev.pocketagent.AppContainer
import dev.pocketagent.core.agent.AgentEvent
import dev.pocketagent.core.agent.AgentMode
import dev.pocketagent.core.llm.ChatMessage
import dev.pocketagent.core.llm.ChatRole
import dev.pocketagent.data.ConversationEntity
import dev.pocketagent.data.MessageEntity
import dev.pocketagent.service.AgentForegroundService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

enum class ItemRole { USER, ASSISTANT, TOOL }

data class ChatItem(
    val id: Long,
    val role: ItemRole,
    val text: String,
    val toolName: String? = null,
    val isError: Boolean = false,
    val streaming: Boolean = false,
    val imagePath: String? = null,
    val time: Long = System.currentTimeMillis()
)

enum class TerminalKind { SYSTEM, THOUGHT, ACTION, OBSERVATION, ERROR }

data class TerminalLine(val id: Long, val kind: TerminalKind, val text: String)

data class ChatUiState(
    val conversationId: Long? = null,
    val isMain: Boolean = true,
    val title: String = "Main chat",
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

    /** Text the UI should drop into the composer (from Ideas, Goals, Feed ▸ Discuss). */
    private val _draft = MutableStateFlow<String?>(null)
    val draft: StateFlow<String?> = _draft.asStateFlow()

    private var job: Job? = null
    private val ids = AtomicLong(1)
    private fun newId() = ids.getAndIncrement()

    init {
        container.appScope.launch { openMain() }
    }

    fun setDraft(text: String?) {
        _draft.value = text
    }

    suspend fun mainConversationId(): Long {
        dao.mainConversation()?.let { return it.id }
        val now = now()
        return dao.insertConversation(
            ConversationEntity(title = "Main chat", mode = AgentMode.WIT.name, isMain = true, createdAt = now, updatedAt = now)
        )
    }

    suspend fun openMain() = openConversation(mainConversationId())

    suspend fun openConversation(id: Long) {
        if (_state.value.running) stop()
        val conversation = dao.conversation(id) ?: return
        val rows = dao.messages(id)
        _state.value = ChatUiState(
            conversationId = id,
            isMain = conversation.isMain,
            title = conversation.title,
            items = rows.map { row ->
                ChatItem(
                    id = newId(),
                    role = when (row.role) {
                        "user" -> ItemRole.USER
                        "tool" -> ItemRole.TOOL
                        else -> ItemRole.ASSISTANT
                    },
                    text = row.content,
                    toolName = row.toolName,
                    isError = row.isError,
                    imagePath = row.imageFile?.let { File(container.workspace.mediaDir, it).absolutePath },
                    time = row.createdAt
                )
            },
            terminal = _state.value.terminal
        )
    }

    /** Creates an empty side chat and switches to it. */
    suspend fun newSideChat(title: String = "New chat"): Long {
        val now = now()
        val id = dao.insertConversation(
            ConversationEntity(title = title, mode = container.settings.current.mode.name, createdAt = now, updatedAt = now)
        )
        openConversation(id)
        return id
    }

    suspend fun renameCurrent(title: String) {
        val id = _state.value.conversationId ?: return
        dao.rename(id, title)
        _state.update { it.copy(title = title) }
    }

    suspend fun clearCurrent() {
        val id = _state.value.conversationId ?: return
        stop()
        dao.clearMessages(id)
        _state.update { it.copy(items = emptyList()) }
    }

    suspend fun deleteConversation(id: Long) {
        val current = _state.value.conversationId
        dao.deleteConversation(id)
        if (current == id) openMain()
    }

    fun clearTerminal() = _state.update { it.copy(terminal = emptyList()) }

    fun send(text: String, mode: AgentMode, imageFile: File? = null) {
        val prompt = text.trim().ifEmpty { if (imageFile != null) "What's in this image?" else "" }
        if (prompt.isEmpty() || _state.value.running) return

        val history = _state.value.items.mapNotNull {
            when (it.role) {
                ItemRole.USER -> ChatMessage(ChatRole.USER, it.text)
                ItemRole.ASSISTANT -> if (it.text.isBlank() || it.isError) null else ChatMessage(ChatRole.ASSISTANT, it.text)
                ItemRole.TOOL -> null
            }
        }.takeLast(MAX_HISTORY)

        val answerId = newId()
        _state.update {
            it.copy(
                items = it.items + ChatItem(newId(), ItemRole.USER, prompt, imagePath = imageFile?.absolutePath) +
                    ChatItem(answerId, ItemRole.ASSISTANT, "", streaming = true),
                running = true,
                step = 0,
                terminal = (it.terminal + TerminalLine(newId(), TerminalKind.SYSTEM, "▶ ${mode.label} · new task")).takeLast(MAX_TERMINAL_LINES)
            )
        }

        if (container.settings.current.keepAwake) startForegroundService()
        job = container.appScope.launch {
            val conversationId = _state.value.conversationId ?: mainConversationId().also { id ->
                _state.update { it.copy(conversationId = id) }
            }
            dao.insertMessage(
                MessageEntity(conversationId = conversationId, role = "user", content = prompt, imageFile = imageFile?.name, createdAt = now())
            )
            val image = imageFile?.let { container.workspace.jpegBase64(it) }?.takeIf { it.isNotEmpty() }
            container.workspace.log("I", "chat ${mode.label}: ${prompt.take(120)}")

            var answer = ""
            try {
                container.runner.newLoop()
                    .run(history, prompt, mode, container.runner.extraContext(), image)
                    .collect { event -> handle(event, answerId, conversationId) { answer = it } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                container.workspace.log("W", "chat stopped by user")
                throw e
            } catch (e: Exception) {
                container.workspace.log("E", "chat failed: ${e.message}")
                handle(AgentEvent.Failed(e.message ?: e.javaClass.simpleName), answerId, conversationId) { }
            } finally {
                val finalText = answer.ifBlank { currentText(answerId) }
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    if (finalText.isNotBlank()) {
                        dao.insertMessage(MessageEntity(conversationId = conversationId, role = "assistant", content = finalText, createdAt = now()))
                    }
                    val conv = dao.conversation(conversationId)
                    if (conv != null && !conv.isMain && (conv.title == "New chat" || conv.title.isBlank())) {
                        dao.updateConversation(conversationId, titleFor(prompt), now())
                        if (_state.value.conversationId == conversationId) _state.update { it.copy(title = titleFor(prompt)) }
                    } else {
                        dao.touch(conversationId, now())
                    }
                }
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
                    terminal = (it.terminal + TerminalLine(newId(), TerminalKind.SYSTEM, "· step ${event.step} via ${event.provider}")).takeLast(MAX_TERMINAL_LINES)
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
                container.workspace.log("I", "tool ${event.name} ${event.argumentsJson.take(200)}")
                _state.update { s ->
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
                        conversationId = conversationId, role = "tool", content = event.output,
                        toolName = event.name, isError = event.isError, createdAt = now()
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
            is AgentEvent.Usage -> container.runner.recordUsage(event)
            is AgentEvent.Completed -> {
                setAnswer(event.answer)
                _state.update {
                    it.copy(terminal = (it.terminal + TerminalLine(newId(), TerminalKind.SYSTEM, "✓ done in ${event.steps} step(s)")).takeLast(MAX_TERMINAL_LINES))
                }
            }
            is AgentEvent.Failed -> {
                container.workspace.log("E", "agent: ${event.message}")
                _state.update { s ->
                    s.copy(
                        items = s.items.map { if (it.id == answerId) it.copy(text = "⚠ ${event.message}", isError = true) else it },
                        terminal = (s.terminal + TerminalLine(newId(), TerminalKind.ERROR, "✗ ${event.message}")).takeLast(MAX_TERMINAL_LINES)
                    )
                }
            }
        }
    }

    private fun currentText(id: Long) = _state.value.items.firstOrNull { it.id == id }?.text.orEmpty()

    private fun titleFor(prompt: String) = prompt.lineSequence().first().take(48)

    private fun now() = System.currentTimeMillis()

    private fun startForegroundService() {
        val ctx = container.app
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, AgentForegroundService::class.java))
        } catch (e: Exception) {
            container.workspace.log("W", "foreground service refused: ${e.message}")
        }
    }

    private fun stopForegroundService() {
        val ctx = container.app
        ctx.stopService(Intent(ctx, AgentForegroundService::class.java))
    }

    companion object {
        private const val MAX_TERMINAL_LINES = 300
        private const val MAX_HISTORY = 40
    }
}

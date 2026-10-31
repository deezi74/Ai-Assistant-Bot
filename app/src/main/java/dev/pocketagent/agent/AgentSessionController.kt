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
    val providerLabel: String = "",
    /** What the agent is doing right now, for the animated avatar: thinking, writing, a tool name, or "phone:<action>". */
    val activity: String? = null
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
    private var learnJob: Job? = null
    /** Tool names the running task has called, for growth and reflection. */
    private val turnTools = mutableListOf<String>()
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

    /** The photo attached to the turn in progress (the imagine tool edits it). */
    @Volatile
    var turnImage: File? = null
        private set

    fun send(text: String, mode: AgentMode, imageFile: File? = null) {
        val prompt = text.trim().ifEmpty { if (imageFile != null) "What's in this image?" else "" }
        if (prompt.isEmpty() || _state.value.running) return
        turnImage = imageFile

        val history = _state.value.items.mapNotNull {
            when (it.role) {
                ItemRole.USER -> ChatMessage(ChatRole.USER, it.text)
                ItemRole.ASSISTANT -> if (it.text.isBlank() || it.isError) null else ChatMessage(ChatRole.ASSISTANT, it.text)
                ItemRole.TOOL -> null
            }
        }.takeLast(MAX_HISTORY)

        cancelLearning()
        synchronized(turnTools) { turnTools.clear() }
        val answerId = newId()
        _state.update {
            it.copy(
                items = it.items + ChatItem(newId(), ItemRole.USER, prompt, imagePath = imageFile?.absolutePath) +
                    ChatItem(answerId, ItemRole.ASSISTANT, "", streaming = true),
                running = true,
                step = 0,
                activity = "thinking",
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
                    .run(history, prompt, mode, container.runner.extraContext(mode), image)
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
                        activity = null,
                        items = s.items.map { if (it.id == answerId) it.copy(streaming = false, text = it.text.ifBlank { finalText }) else it }
                            .filterNot { it.id == answerId && it.text.isBlank() }
                    )
                }
                stopForegroundService()
                if (answer.isNotBlank()) afterTask(prompt, answer, conversationId)
            }
        }
    }

    // region Learning

    private data class PendingReflection(val prompt: String, val answer: String, val tools: List<String>, val conversationId: Long)

    private val pending = ArrayDeque<PendingReflection>()
    private var idleJob: Job? = null

    private fun cancelLearning() {
        idleJob?.cancel()
        idleJob = null
        val running = learnJob
        if (running != null && running.isActive) {
            running.cancel()
            container.localEngine.abort()
        }
        learnJob = null
    }

    /**
     * Counts the task toward growth, then reflects on it. With a cloud model that
     * happens right away; with the on-phone model it waits until the phone is idle
     * or the app is in the background, because a reflection pass would evict the
     * chat from the model's cache and make the next reply slow.
     */
    private fun afterTask(prompt: String, answer: String, conversationId: Long) {
        val tools = synchronized(turnTools) { turnTools.toList() }
        val learner = container.learner
        val levelBefore = learner.growth.value.level
        learner.recordTask(tools.size, tools.any { it == "computer" || it == "run_code" })
        if (!learner.shouldReflect(prompt, answer, tools.size)) {
            announceGrowth(null, levelBefore, conversationId)
            return
        }
        learnJob = container.appScope.launch {
            if (container.scheduler.activeProvider() === container.localEngine) {
                synchronized(pending) {
                    pending.addLast(PendingReflection(prompt, answer, tools, conversationId))
                    while (pending.size > MAX_PENDING) pending.removeFirst()
                }
                announceGrowth(null, levelBefore, conversationId)
                idleJob?.cancel()
                idleJob = container.appScope.launch {
                    kotlinx.coroutines.delay(IDLE_REFLECT_MS)
                    flushLearning()
                }
                return@launch
            }
            kotlinx.coroutines.delay(REFLECT_DELAY_MS)
            reflectNow(PendingReflection(prompt, answer, tools, conversationId), levelBefore)
        }
    }

    /** Runs queued reflections now, if nothing else is using the model. Called when the app goes to the background. */
    fun flushLearning() {
        if (_state.value.running || learnJob?.isActive == true) return
        val batch = synchronized(pending) { pending.toList().also { pending.clear() } }
        if (batch.isEmpty()) return
        learnJob = container.appScope.launch {
            for (item in batch) {
                reflectNow(item, container.learner.growth.value.level)
            }
        }
    }

    private suspend fun reflectNow(item: PendingReflection, levelBefore: Int) {
        _state.update { if (!it.running) it.copy(activity = "learning") else it }
        val learned = try {
            container.learner.reflect(item.prompt, item.answer, item.tools)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            container.workspace.log("W", "reflection skipped: ${e.message}")
            null
        } finally {
            _state.update { if (!it.running && it.activity == "learning") it.copy(activity = null) else it }
        }
        announceGrowth(learned, levelBefore, item.conversationId)
    }

    private fun announceGrowth(learned: Learned?, levelBefore: Int, conversationId: Long) {
        val g = container.learner.growth.value
        val levelUp = g.level > levelBefore
        if ((learned == null || learned.isEmpty) && !levelUp) return
        val text = buildString {
            if (levelUp) append("Level up! Now level ").append(g.level).append(" · ").append(g.title).append('.')
            if (learned != null && !learned.isEmpty) {
                if (isNotEmpty()) append(' ')
                append("Learned ").append(learned.summary()).append('.')
                learned.facts.forEach { append("\n• ").append(it) }
                learned.lesson?.let { append("\n• Lesson: ").append(it) }
            }
        }
        _state.update { s ->
            if (s.conversationId != conversationId || s.running) s
            else s.copy(
                items = s.items + ChatItem(newId(), ItemRole.TOOL, text, toolName = "growth"),
                activity = if (levelUp) "levelup" else s.activity
            )
        }
        if (levelUp) {
            container.appScope.launch {
                kotlinx.coroutines.delay(LEVEL_UP_SHOW_MS)
                _state.update { if (it.activity == "levelup") it.copy(activity = null) else it }
            }
        }
    }

    // endregion

    fun stop() {
        container.localEngine.abort()
        job?.cancel()
        job = null
    }

    private suspend fun handle(event: AgentEvent, answerId: Long, conversationId: Long, setAnswer: (String) -> Unit) {
        when (event) {
            is AgentEvent.StepStarted -> _state.update {
                it.copy(
                    activity = "thinking",
                    step = event.step,
                    providerLabel = event.provider,
                    terminal = (it.terminal + TerminalLine(newId(), TerminalKind.SYSTEM, "· step ${event.step} via ${event.provider}")).takeLast(MAX_TERMINAL_LINES)
                )
            }
            is AgentEvent.AnswerDelta -> _state.update { s ->
                s.copy(activity = "writing", items = s.items.map { if (it.id == answerId) it.copy(text = it.text + event.text) else it })
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
                synchronized(turnTools) { turnTools += event.name }
                _state.update { s ->
                    val narration = s.items.firstOrNull { it.id == answerId }?.text.orEmpty().trim()
                    val terminal = s.terminal +
                        (if (narration.isNotEmpty()) listOf(TerminalLine(newId(), TerminalKind.THOUGHT, narration)) else emptyList()) +
                        TerminalLine(newId(), TerminalKind.ACTION, "→ ${event.name} ${event.argumentsJson}")
                    s.copy(
                        activity = activityFor(event.name, event.argumentsJson),
                        items = s.items.map { if (it.id == answerId) it.copy(text = "") else it },
                        terminal = terminal.takeLast(MAX_TERMINAL_LINES)
                    )
                }
            }
            is AgentEvent.Observation -> {
                val made = Regex(Regex.escape(ImagineTool.IMAGE_MARKER) + "([A-Za-z0-9_.-]+)]").find(event.output)?.groupValues?.get(1)
                    ?.takeIf { File(container.workspace.mediaDir, it).isFile }
                dao.insertMessage(
                    MessageEntity(
                        conversationId = conversationId, role = "tool", content = event.output,
                        toolName = event.name, isError = event.isError, imageFile = made, createdAt = now()
                    )
                )
                _state.update { s ->
                    val toolItem = ChatItem(
                        newId(), ItemRole.TOOL, event.output, event.name, event.isError,
                        imagePath = made?.let { File(container.workspace.mediaDir, it).absolutePath }
                    )
                    val idx = s.items.indexOfFirst { it.id == answerId }
                    val items = if (idx >= 0) s.items.toMutableList().apply { add(idx, toolItem) } else s.items + toolItem
                    s.copy(
                        activity = "thinking",
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

    private fun activityFor(tool: String, argumentsJson: String): String {
        if (tool == "computer") {
            val command = Regex("\"command\"\\s*:\\s*\"([^\"]*)").find(argumentsJson)?.groupValues?.get(1).orEmpty().trim()
            return when {
                argumentsJson.contains("\"write_path\"") && command.isEmpty() -> "computer:write"
                command.startsWith("run") || command.startsWith("node") || command.startsWith("eval") -> "computer:run"
                command.startsWith("rm") -> "computer:delete"
                command.startsWith("cat") || command.startsWith("ls") || command.startsWith("tree") || command.startsWith("grep") -> "computer:read"
                argumentsJson.contains("\"write_path\"") -> "computer:write"
                else -> "computer"
            }
        }
        if (tool != "phone") return tool
        val action = Regex("\"action\"\\s*:\\s*\"([a-z_]+)\"").find(argumentsJson)?.groupValues?.get(1) ?: "phone"
        return "phone:$action"
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
        private const val REFLECT_DELAY_MS = 1_500L
        private const val IDLE_REFLECT_MS = 180_000L
        private const val MAX_PENDING = 4
        private const val LEVEL_UP_SHOW_MS = 4_000L
    }
}

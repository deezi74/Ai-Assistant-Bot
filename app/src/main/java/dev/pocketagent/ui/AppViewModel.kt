package dev.pocketagent.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pocketagent.PocketAgentApp
import dev.pocketagent.agent.ChatUiState
import dev.pocketagent.core.agent.AgentMode
import dev.pocketagent.core.llm.HfFile
import dev.pocketagent.core.llm.HfReference
import dev.pocketagent.core.llm.RemoteKind
import dev.pocketagent.data.AppSettings
import dev.pocketagent.data.ArtifactEntity
import dev.pocketagent.data.ConversationEntity
import dev.pocketagent.data.FeedPostEntity
import dev.pocketagent.data.GoalEntity
import dev.pocketagent.data.LogLine
import dev.pocketagent.data.MediaEntity
import dev.pocketagent.data.RoutineEntity
import dev.pocketagent.data.Skill
import dev.pocketagent.data.TokenUsageEntity
import dev.pocketagent.llm.DownloadStatus
import dev.pocketagent.llm.LocalModelFile
import dev.pocketagent.llm.LocalModelState
import dev.pocketagent.llm.RecommendedModel
import dev.pocketagent.llm.RecommendedModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class Tab(val label: String) { CHAT("Chat"), FEED("Feed"), IDEAS("Ideas"), GOALS("Goals"), LIBRARY("Library") }

data class HfBrowserState(
    val query: String = "",
    val loading: Boolean = false,
    val reference: HfReference? = null,
    val files: List<HfFile> = emptyList(),
    val error: String? = null
)

data class StorageReport(
    val modelsBytes: Long = 0,
    val artifactsBytes: Long = 0,
    val mediaBytes: Long = 0,
    val databaseBytes: Long = 0,
    val personaBytes: Long = 0,
    val freeBytes: Long = 0
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as PocketAgentApp).container
    private val dao = container.database.conversations()
    private val content = container.database.content()
    val workspace get() = container.workspace

    // region Navigation

    private val _tab = MutableStateFlow(Tab.CHAT)
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    /** Settings routes pushed on top of the tab shell. Empty = shell visible. */
    private val _stack = MutableStateFlow<List<String>>(emptyList())
    val stack: StateFlow<List<String>> = _stack.asStateFlow()

    fun selectTab(tab: Tab) {
        _stack.value = emptyList()
        _tab.value = tab
    }

    fun push(route: String) {
        _stack.value = _stack.value + route
    }

    fun pop(): Boolean {
        if (_stack.value.isEmpty()) return false
        _stack.value = _stack.value.dropLast(1)
        return true
    }

    // endregion

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    fun consumeToast() {
        _toast.value = null
    }
    fun toast(message: String) {
        _toast.value = message
    }

    val settings: StateFlow<AppSettings> = container.settings.settings
    fun updateSettings(transform: (AppSettings) -> AppSettings) = container.settings.update(transform)

    // region Chat

    val chat: StateFlow<ChatUiState> = container.session.state
    val draft: StateFlow<String?> = container.session.draft
    val sideChats: StateFlow<List<ConversationEntity>> =
        dao.observeSideChats(false).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val archivedChats: StateFlow<List<ConversationEntity>> =
        dao.observeSideChats(true).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _searchResults = MutableStateFlow<List<ConversationEntity>?>(null)
    val searchResults: StateFlow<List<ConversationEntity>?> = _searchResults.asStateFlow()

    private val _pendingImage = MutableStateFlow<File?>(null)
    val pendingImage: StateFlow<File?> = _pendingImage.asStateFlow()

    fun send(text: String) {
        val image = _pendingImage.value
        _pendingImage.value = null
        container.session.send(text, settings.value.mode, image)
    }

    fun stop() = container.session.stop()
    fun clearTerminal() = container.session.clearTerminal()
    fun setMode(mode: AgentMode) = container.settings.update { it.copy(mode = mode) }
    fun consumeDraft() = container.session.setDraft(null)

    /** Drops text into the composer and switches to Chat. */
    fun prefill(text: String, newSideChat: Boolean = false) {
        viewModelScope.launch {
            if (newSideChat) container.session.newSideChat()
            container.session.setDraft(text)
            selectTab(Tab.CHAT)
        }
    }

    fun openMain() = viewModelScope.launch {
        container.session.openMain()
        selectTab(Tab.CHAT)
    }

    fun openConversation(id: Long) = viewModelScope.launch {
        container.session.openConversation(id)
        selectTab(Tab.CHAT)
    }

    fun newSideChat() = viewModelScope.launch {
        container.session.newSideChat()
        selectTab(Tab.CHAT)
    }

    fun renameCurrent(title: String) = viewModelScope.launch { container.session.renameCurrent(title.trim().ifEmpty { "Untitled" }) }
    fun renameConversation(id: Long, title: String) = viewModelScope.launch {
        dao.rename(id, title.trim().ifEmpty { "Untitled" })
        if (chat.value.conversationId == id) container.session.openConversation(id)
    }
    fun clearCurrent() = viewModelScope.launch { container.session.clearCurrent() }
    fun deleteConversation(id: Long) = viewModelScope.launch { container.session.deleteConversation(id) }
    fun setArchived(id: Long, archived: Boolean) = viewModelScope.launch {
        dao.setArchived(id, archived)
        if (archived && chat.value.conversationId == id) container.session.openMain()
    }

    fun search(query: String) {
        if (query.isBlank()) {
            _searchResults.value = null
            return
        }
        viewModelScope.launch { _searchResults.value = dao.search(query.trim()) }
    }

    fun attachImage(uri: Uri) = viewModelScope.launch {
        try {
            val file = importMediaFile(uri)
            _pendingImage.value = file
        } catch (e: Exception) {
            toast("Couldn't attach image: ${e.message}")
        }
    }

    fun attachExisting(media: MediaEntity) {
        _pendingImage.value = File(workspace.mediaDir, media.fileName)
        selectTab(Tab.CHAT)
    }

    fun clearPendingImage() {
        _pendingImage.value = null
    }

    /** Handles ACTION_SEND / ACTION_ASSIST launches. */
    fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_ASSIST -> selectTab(Tab.CHAT)
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                @Suppress("DEPRECATION")
                val stream = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                if (stream != null && intent.type?.startsWith("image/") == true) attachImage(stream)
                if (!text.isNullOrBlank()) container.session.setDraft(text)
                selectTab(Tab.CHAT)
            }
        }
    }

    // endregion

    // region Feed

    val feed: StateFlow<List<FeedPostEntity>> = content.observeFeed().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _feedBusy = MutableStateFlow(false)
    val feedBusy: StateFlow<Boolean> = _feedBusy.asStateFlow()
    private val _feedError = MutableStateFlow<String?>(null)
    val feedError: StateFlow<String?> = _feedError.asStateFlow()

    fun refreshFeed() {
        if (_feedBusy.value) return
        _feedBusy.value = true
        _feedError.value = null
        viewModelScope.launch {
            try {
                container.ensureModelLoaded()
                container.feed.generate()
            } catch (e: Exception) {
                _feedError.value = e.message ?: "Feed failed"
            } finally {
                _feedBusy.value = false
            }
        }
    }

    fun react(post: FeedPostEntity, reaction: Int) = viewModelScope.launch {
        content.react(post.id, if (post.reaction == reaction) 0 else reaction)
    }
    fun deletePost(post: FeedPostEntity) = viewModelScope.launch { content.deletePost(post.id) }
    fun clearFeed() = viewModelScope.launch { content.clearFeed() }
    fun discuss(post: FeedPostEntity) = prefill("Tell me more about \"${post.title}\": ${post.body}", newSideChat = true)

    // endregion

    // region Goals & routines

    val goals: StateFlow<List<GoalEntity>> = content.observeGoals().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val routines: StateFlow<List<RoutineEntity>> = content.observeRoutines().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addGoal(title: String, note: String, category: String) = viewModelScope.launch {
        content.insertGoal(GoalEntity(title = title.trim(), note = note.trim(), category = category, createdAt = System.currentTimeMillis()))
    }
    fun toggleGoal(goal: GoalEntity) = viewModelScope.launch { content.updateGoal(goal.copy(done = !goal.done)) }
    fun deleteGoal(goal: GoalEntity) = viewModelScope.launch { content.deleteGoal(goal.id) }

    fun describeRoutine(r: RoutineEntity): String = container.routines.describe(r)

    fun saveRoutine(r: RoutineEntity) = viewModelScope.launch {
        val saved = if (r.id == 0L) r.copy(id = content.insertRoutine(r)) else r.also { content.updateRoutine(it) }
        container.routines.schedule(saved)
        toast("Routine saved")
    }

    fun toggleRoutine(r: RoutineEntity) = viewModelScope.launch {
        val next = r.copy(enabled = !r.enabled)
        content.updateRoutine(next)
        container.routines.schedule(next)
    }

    fun runRoutineNow(r: RoutineEntity) {
        container.routines.runNow(r)
        toast("Running \"${r.name}\" in the background")
    }

    fun deleteRoutine(r: RoutineEntity) = viewModelScope.launch {
        container.routines.cancel(r.id)
        content.deleteRoutine(r.id)
    }

    // endregion

    // region Library

    val artifacts: StateFlow<List<ArtifactEntity>> = content.observeArtifacts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val media: StateFlow<List<MediaEntity>> = content.observeMedia().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun artifactFile(a: ArtifactEntity) = File(workspace.artifactsDir, a.fileName)
    fun mediaFile(m: MediaEntity) = File(workspace.mediaDir, m.fileName)

    suspend fun readArtifact(a: ArtifactEntity): String = withContext(Dispatchers.IO) {
        val f = artifactFile(a)
        if (f.isFile) f.readText() else "(file missing)"
    }

    fun deleteArtifact(a: ArtifactEntity) = viewModelScope.launch {
        withContext(Dispatchers.IO) { artifactFile(a).delete() }
        content.deleteArtifact(a.id)
    }

    fun deleteMedia(m: MediaEntity) = viewModelScope.launch {
        withContext(Dispatchers.IO) { mediaFile(m).delete() }
        content.deleteMedia(m.id)
    }

    fun importMedia(uri: Uri) = viewModelScope.launch {
        try {
            importMediaFile(uri)
            toast("Added to Media")
        } catch (e: Exception) {
            toast("Import failed: ${e.message}")
        }
    }

    fun newNote(title: String, body: String) = viewModelScope.launch {
        container.saveArtifact(title.trim().ifEmpty { "Note" }, body, "md")
        toast("Saved to Library")
    }

    private suspend fun importMediaFile(uri: Uri): File {
        val file = workspace.importImage(uri)
        content.insertMedia(MediaEntity(fileName = file.name, createdAt = System.currentTimeMillis()))
        return file
    }

    // endregion

    // region Avatar & identity

    val avatarVersion: StateFlow<Int> = workspace.avatarVersion
    fun avatarPhoto(s: AppSettings): File? = if (s.avatarKind == "photo") workspace.avatarFile(s.avatarValue) else null

    fun setAvatarPhoto(uri: Uri) = viewModelScope.launch {
        try {
            val name = workspace.importAvatar(uri)
            updateSettings { it.copy(avatarKind = "photo", avatarValue = name) }
        } catch (e: Exception) {
            toast("Couldn't use that photo: ${e.message}")
        }
    }

    fun setBuiltinAvatar(id: String) = updateSettings { it.copy(avatarKind = "builtin", avatarValue = id) }

    // endregion

    // region Soul, memory, skills, env vars

    fun readSoul(): String = container.persona.readSoul()
    fun saveSoul(text: String) {
        container.persona.writeSoul(text)
        toast("Soul saved")
    }
    fun resetSoul(): String {
        container.persona.resetSoul()
        toast("Soul reset to default")
        return container.persona.readSoul()
    }

    fun readMemory(): String = workspace.readMemory()
    fun saveMemory(text: String) {
        workspace.writeMemory(text)
        toast("Memory saved")
    }

    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()
    fun refreshSkills() = viewModelScope.launch { _skills.value = withContext(Dispatchers.IO) { workspace.skills() } }
    fun saveSkill(original: Skill?, name: String, description: String, instructions: String, enabled: Boolean) = viewModelScope.launch {
        withContext(Dispatchers.IO) { workspace.saveSkill(original, name.trim().ifEmpty { "Skill" }, description.trim(), instructions, enabled) }
        refreshSkills()
    }
    fun toggleSkill(skill: Skill) = viewModelScope.launch {
        withContext(Dispatchers.IO) { workspace.setSkillEnabled(skill, !skill.enabled) }
        refreshSkills()
    }
    fun deleteSkill(skill: Skill) = viewModelScope.launch {
        withContext(Dispatchers.IO) { workspace.deleteSkill(skill) }
        refreshSkills()
    }

    private val _env = MutableStateFlow(container.settings.envVars)
    val env: StateFlow<Map<String, String>> = _env.asStateFlow()
    fun putEnv(name: String, value: String) {
        val key = name.trim().uppercase().replace(Regex("[^A-Z0-9_]"), "_")
        if (key.isEmpty()) return
        val next = _env.value + (key to value)
        container.settings.envVars = next
        _env.value = next
    }
    fun removeEnv(name: String) {
        val next = _env.value - name
        container.settings.envVars = next
        _env.value = next
    }

    /** Text files the agent reads or writes, for Settings ▸ System files. */
    fun systemFiles(): List<File> = listOf(container.persona.soulFile, workspace.memoryFile) +
        workspace.skills().map { File(workspace.skillsDir, it.fileName) }

    // endregion

    // region Models & providers

    val localState: StateFlow<LocalModelState> = container.localEngine.state
    val downloads: StateFlow<List<DownloadStatus>> =
        container.downloads.downloads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _localModels = MutableStateFlow<List<LocalModelFile>>(emptyList())
    val localModels: StateFlow<List<LocalModelFile>> = _localModels.asStateFlow()

    private val _hf = MutableStateFlow(HfBrowserState(query = "bartowski/Qwen2.5-1.5B-Instruct-GGUF"))
    val hf: StateFlow<HfBrowserState> = _hf.asStateFlow()

    private val _importProgress = MutableStateFlow<Float?>(null)
    val importProgress: StateFlow<Float?> = _importProgress.asStateFlow()

    // region Recommended models

    /** Total RAM of this phone, used to suggest a model that fits. */
    val totalRamBytes: Long = container.localEngine.memoryReport().totalBytes
    val bestModel: RecommendedModel = RecommendedModels.bestFor(totalRamBytes)
    private val autoLoaded = mutableSetOf<String>()

    fun downloadRecommended(model: RecommendedModel) {
        val wifiOnly = settings.value.wifiOnlyDownloads
        container.downloads.enqueue(model.reference, model.hfFile, wifiOnly)
        toast("Downloading ${model.name} (${formatBytes(model.sizeBytes)})" + if (wifiOnly) ", waits for Wi-Fi" else ". It loads automatically when done.")
    }

    fun loadRecommended(model: RecommendedModel) {
        val file = File(container.downloads.modelsDir, model.file)
        if (file.isFile) loadModel(LocalModelFile(file)) else downloadRecommended(model)
    }

    fun isDownloaded(model: RecommendedModel): Boolean =
        localModels.value.any { it.name == model.file && it.sizeBytes >= model.sizeBytes * 99 / 100 }

    /** Loads a just-downloaded recommended model when nothing is loaded yet, so the agent can answer right away. */
    private fun autoLoadIfRecommended(fileName: String) {
        if (RecommendedModels.byFile(fileName) == null || !autoLoaded.add(fileName)) return
        val state = container.localEngine.state.value
        if (state is LocalModelState.Ready || state is LocalModelState.Loading) return
        val file = File(container.downloads.modelsDir, fileName)
        if (!file.isFile) return
        loadModel(LocalModelFile(file))
        toast("${RecommendedModels.byFile(fileName)?.name} is ready")
    }

    // endregion

    init {
        refreshLocalModels()
        refreshSkills()
        viewModelScope.launch {
            downloads.collect { list ->
                val finished = list.filter { it.state == androidx.work.WorkInfo.State.SUCCEEDED }
                if (finished.isNotEmpty()) refreshLocalModels()
                finished.forEach { d -> autoLoadIfRecommended(d.fileName) }
            }
        }
    }


    fun selectRemoteKind(kind: RemoteKind) = container.settings.update {
        it.copy(remoteKind = kind, remoteBaseUrl = kind.defaultBaseUrl, remoteModel = kind.defaultModel)
    }

    fun hasToken(kind: RemoteKind): Boolean = container.settings.hasToken(kind)
    fun saveToken(kind: RemoteKind, token: String) {
        container.settings.setToken(kind, token)
        toast(if (token.isBlank()) "${kind.label} key removed" else "${kind.label} key saved (encrypted)")
    }

    val hasHfToken: Boolean get() = !container.settings.huggingFaceToken.isNullOrBlank()
    fun saveHfToken(token: String) {
        container.settings.huggingFaceToken = token
        toast(if (token.isBlank()) "Hugging Face token removed" else "Hugging Face token saved")
    }

    fun refreshLocalModels() {
        viewModelScope.launch { _localModels.value = withContext(Dispatchers.IO) { container.downloads.localModels() } }
    }

    fun loadModel(model: LocalModelFile) {
        val s = settings.value
        container.settings.update { it.copy(lastModelPath = model.file.absolutePath) }
        viewModelScope.launch { container.localEngine.load(model.file.absolutePath, s.contextSize, s.threads) }
    }

    fun unloadModel() {
        viewModelScope.launch { container.localEngine.unload() }
    }

    fun deleteModel(model: LocalModelFile) {
        viewModelScope.launch {
            if (container.localEngine.currentModelPath == model.file.absolutePath) container.localEngine.unload()
            withContext(Dispatchers.IO) { container.downloads.delete(model) }
            refreshLocalModels()
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _importProgress.value = 0f
            try {
                val file = container.downloads.importFromUri(uri) { _importProgress.value = it }
                toast("Imported ${file.name}")
                refreshLocalModels()
            } catch (e: Exception) {
                toast("Import failed: ${e.message}")
            } finally {
                _importProgress.value = null
            }
        }
    }

    fun setHfQuery(q: String) {
        _hf.value = _hf.value.copy(query = q)
    }

    fun listHfFiles() {
        val query = _hf.value.query
        _hf.value = _hf.value.copy(loading = true, error = null, files = emptyList())
        viewModelScope.launch {
            try {
                val (ref, files) = container.downloads.listFiles(query)
                _hf.value = _hf.value.copy(loading = false, reference = ref, files = files)
            } catch (e: Exception) {
                _hf.value = _hf.value.copy(loading = false, error = e.message ?: "Lookup failed")
            }
        }
    }

    fun download(file: HfFile) {
        val ref = _hf.value.reference ?: return
        container.downloads.enqueue(ref, file, settings.value.wifiOnlyDownloads)
        toast("Downloading ${file.path.substringAfterLast('/')}" + if (settings.value.wifiOnlyDownloads) " (waits for Wi-Fi)" else "")
    }

    fun cancelDownload(id: UUID) = container.downloads.cancel(id)
    fun clearFinishedDownloads() = container.downloads.clearFinished()

    // endregion

    // region Usage, storage, logs, data

    val usage: StateFlow<List<TokenUsageEntity>> = content.observeUsage().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun clearUsage() = viewModelScope.launch { content.clearUsage() }

    val logs: StateFlow<List<LogLine>> = workspace.logs
    fun clearLogs() = workspace.clearLogs()

    private val _storage = MutableStateFlow(StorageReport())
    val storage: StateFlow<StorageReport> = _storage.asStateFlow()

    fun refreshStorage() = viewModelScope.launch {
        _storage.value = withContext(Dispatchers.IO) {
            val app = getApplication<Application>()
            val db = app.getDatabasePath(dev.pocketagent.data.AppDatabase.NAME)
            StorageReport(
                modelsBytes = workspace.dirSize(container.downloads.modelsDir),
                artifactsBytes = workspace.dirSize(workspace.artifactsDir),
                mediaBytes = workspace.dirSize(workspace.mediaDir),
                databaseBytes = listOf(db, File(db.path + "-wal"), File(db.path + "-shm")).sumOf { if (it.isFile) it.length() else 0L },
                personaBytes = workspace.dirSize(workspace.personaDir) + workspace.dirSize(workspace.avatarDir),
                freeBytes = container.downloads.modelsDir.usableSpace
            )
        }
    }

    fun clearMediaFiles() = viewModelScope.launch {
        media.value.forEach { m ->
            withContext(Dispatchers.IO) { mediaFile(m).delete() }
            content.deleteMedia(m.id)
        }
        refreshStorage()
    }

    fun deleteAllSideChats() = viewModelScope.launch {
        dao.deleteSideChats()
        container.session.openMain()
        toast("Side chats deleted")
    }

    fun clearMainChat() = viewModelScope.launch {
        val id = container.session.mainConversationId()
        dao.clearMessages(id)
        container.session.openMain()
        toast("Main chat cleared")
    }

    fun clearMemory() {
        workspace.writeMemory("")
        toast("Memory cleared")
    }

    /** Deletes chats, feed, goals, Library, memory and settings. Downloaded models are kept. */
    fun eraseEverything() = viewModelScope.launch {
        container.session.stop()
        withContext(Dispatchers.IO) {
            container.database.clearAllTables()
            workspace.writeMemory("")
            workspace.artifactsDir.listFiles()?.forEach { it.delete() }
            workspace.mediaDir.listFiles()?.forEach { it.delete() }
            workspace.avatarDir.listFiles()?.forEach { it.delete() }
            container.settings.wipe()
            container.settings.envVars = emptyMap()
        }
        _env.value = emptyMap()
        container.session.openMain()
        toast("Everything erased")
    }

    fun exportBackup(target: Uri) = viewModelScope.launch {
        try {
            val n = container.backup.export(target)
            toast("Backup written ($n files)")
        } catch (e: Exception) {
            toast("Backup failed: ${e.message}")
        }
    }

    /** Returns via [onRestartNeeded] when the database was replaced. */
    fun restoreBackup(source: Uri, onRestartNeeded: () -> Unit) = viewModelScope.launch {
        try {
            val restart = container.backup.restore(source)
            if (restart) onRestartNeeded() else toast("Restored")
        } catch (e: Exception) {
            toast("Restore failed: ${e.message}")
        }
    }

    // endregion

    // region Mounted folders

    private val _mounts = MutableStateFlow(workspace.mounts())
    val mounts: StateFlow<List<String>> = _mounts.asStateFlow()

    fun addMount(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            toast("Android didn't grant lasting access to that folder")
            return
        }
        workspace.addMount(uri.toString())
        _mounts.value = workspace.mounts()
        toast("Folder shared with the agent (read-only)")
    }

    fun removeMount(uri: String) {
        try {
            getApplication<Application>().contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            workspace.log("W", "release permission: ${e.message}")
        }
        workspace.removeMount(uri)
        _mounts.value = workspace.mounts()
    }

    // endregion
}

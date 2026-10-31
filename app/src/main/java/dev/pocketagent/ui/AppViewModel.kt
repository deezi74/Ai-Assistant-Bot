package dev.pocketagent.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pocketagent.PocketAgentApp
import dev.pocketagent.agent.CharacterGenerator
import dev.pocketagent.agent.CharacterStyle
import dev.pocketagent.agent.ChatUiState
import dev.pocketagent.agent.ComputerEntry
import dev.pocketagent.agent.EmailClient
import dev.pocketagent.agent.EmailPreset
import dev.pocketagent.data.EmailAccount
import dev.pocketagent.agent.Growth
import dev.pocketagent.agent.ImageSource
import dev.pocketagent.agent.Lesson
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

    fun startTask(text: String) {
        val image = _pendingImage.value
        _pendingImage.value = null
        viewModelScope.launch { container.session.startTask(text, settings.value.mode, image) }
    }

    /** The app left the screen: a good moment for deferred on-phone learning. */
    fun onAppBackground() = container.session.flushLearning()
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

    fun refreshFeed() = refreshFeed(trending = false)

    fun refreshFeed(trending: Boolean) {
        if (_feedBusy.value) return
        _feedBusy.value = true
        _feedError.value = null
        viewModelScope.launch {
            try {
                container.ensureModelLoaded()
                container.feed.generate(trending = trending)
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

    // region AI characters

    val characterStyles: List<CharacterStyle> = CharacterGenerator.styles
    fun imageSources(): List<ImageSource> = container.characters.sources()

    private val _character = MutableStateFlow<CharacterState>(CharacterState.Idle)
    val character: StateFlow<CharacterState> = _character.asStateFlow()

    private val _characterGallery = MutableStateFlow(workspace.characters())
    val characterGallery: StateFlow<List<File>> = _characterGallery.asStateFlow()

    fun generateCharacter(description: String, styleId: String, sourceId: String) {
        if (_character.value is CharacterState.Drawing) return
        if (settings.value.offlineMode) {
            toast("Offline mode is on. Turn it off in Network to draw a character.")
            return
        }
        val style = CharacterGenerator.style(styleId)
        _character.value = CharacterState.Drawing(description)
        updateSettings { it.copy(imageSource = sourceId) }
        viewModelScope.launch {
            try {
                val bytes = container.characters.generate(description, style, kotlin.random.Random.nextInt(1, 1_000_000), sourceId)
                val file = workspace.saveCharacter(bytes, description)
                _characterGallery.value = workspace.characters()
                _character.value = CharacterState.Preview(file, description)
            } catch (e: Exception) {
                workspace.log("W", "character generation failed: ${e.message}")
                _character.value = CharacterState.Failed(e.message ?: "Could not draw the character")
            }
        }
    }

    fun useCharacter(file: File, description: String = workspace.characterDescription(file)) = viewModelScope.launch {
        try {
            val name = workspace.importAvatarFile(file)
            updateSettings { it.copy(avatarKind = "photo", avatarValue = name, characterDescription = description.take(300)) }
            _character.value = CharacterState.Idle
            toast("${settings.value.assistantName} has a new look")
        } catch (e: Exception) {
            toast("Couldn't use that character: ${e.message}")
        }
    }

    fun deleteCharacter(file: File) = viewModelScope.launch(Dispatchers.IO) {
        workspace.deleteCharacter(file)
        _characterGallery.value = workspace.characters()
        val current = _character.value
        if (current is CharacterState.Preview && current.file == file) _character.value = CharacterState.Idle
    }

    fun dismissCharacter() {
        if (_character.value !is CharacterState.Drawing) _character.value = CharacterState.Idle
    }

    // endregion

    // region Computer & code

    val computerTranscript: StateFlow<List<ComputerEntry>> = container.computer.transcript
    val computerRevision: StateFlow<Int> = container.computer.revision
    val computerRoot: File get() = container.computer.root
    fun computerPrompt(): String = container.computer.computer.prompt

    fun runComputerCommand(command: String) = viewModelScope.launch { container.computer.run(command) }
    fun clearComputer() = container.computer.clearTranscript()

    fun codeProjects(): List<File> = container.computer.projects()
    fun codeFiles(project: File): List<File> = container.computer.files(project)
    suspend fun readCode(file: File): String = container.computer.readFile(file)
    suspend fun createProject(name: String): File = container.computer.createProject(name)
    suspend fun runCode(file: File): String = container.computer.runFile(file)

    /** Saves and returns true, or shows why it could not. Run only after this returns. */
    suspend fun saveCode(file: File, content: String): Boolean = try {
        container.computer.writeFile(file, content)
        true
    } catch (e: Exception) {
        toast("Couldn't save: ${e.message}")
        false
    }

    fun codeFileIn(project: File, name: String): File? = try {
        container.computer.fileIn(project, name)
    } catch (e: IllegalArgumentException) {
        toast("That name points outside the computer")
        null
    }

    fun deleteCode(file: File) = viewModelScope.launch {
        try {
            container.computer.delete(file)
        } catch (e: Exception) {
            toast("Couldn't delete: ${e.message}")
        }
    }

    /** Hands a coding task to the agent in a side chat, pointed at its computer. */
    fun askAgentToBuild(task: String, project: File?) {
        val where = project?.let { " in the /${it.name} project" }.orEmpty()
        prefill("Use your computer to $task$where. Write the files, run the program to check it works, then tell me how to use it.", newSideChat = true)
    }

    // endregion

    // region Connectors: email, location, places, image keys

    val emailPresets: List<EmailPreset> = EmailClient.presets
    private fun publicAccounts() = container.settings.emailAccounts.map { it.copy(password = "") }
    private val _emailAccounts = MutableStateFlow(publicAccounts())
    /** Connected accounts with their passwords blanked out. */
    val emailAccounts: StateFlow<List<EmailAccount>> = _emailAccounts.asStateFlow()
    private val _emailStatus = MutableStateFlow<String?>(null)
    val emailStatus: StateFlow<String?> = _emailStatus.asStateFlow()

    /** Adds an account, or replaces the one with the same address, then tests it. */
    fun connectEmail(host: String, port: Int, user: String, password: String) {
        val address = user.trim()
        val all = container.settings.emailAccounts
        val existing = all.firstOrNull { it.user.equals(address, ignoreCase = true) }
        val pass = password.replace(" ", "").ifBlank { existing?.password.orEmpty() }
        if (host.isBlank() || address.isBlank() || pass.isBlank()) {
            _emailStatus.value = "Fill in the server, address and app password."
            return
        }
        val account = EmailAccount(host.trim(), port, address, pass)
        container.settings.emailAccounts = all.filterNot { it.user.equals(address, ignoreCase = true) } + account
        _emailAccounts.value = publicAccounts()
        _emailStatus.value = "Testing $address…"
        viewModelScope.launch {
            _emailStatus.value = try {
                container.email.test(account)
            } catch (e: javax.mail.AuthenticationFailedException) {
                "Sign-in failed for $address. Use an app password, not your normal password."
            } catch (e: Exception) {
                "Could not connect $address: ${e.message}"
            }
        }
    }

    fun disconnectEmail(user: String) {
        container.settings.emailAccounts = container.settings.emailAccounts.filterNot { it.user.equals(user, ignoreCase = true) }
        _emailAccounts.value = publicAccounts()
        _emailStatus.value = "Removed $user and its app password from this phone."
    }

    fun hasLocationPermission(): Boolean = container.location.hasPermission()
    fun isLocationOn(): Boolean = container.location.isLocationOn()

    private val _whereAmI = MutableStateFlow<String?>(null)
    val whereAmI: StateFlow<String?> = _whereAmI.asStateFlow()
    fun testLocation() = viewModelScope.launch {
        _whereAmI.value = "Finding you…"
        val p = container.location.current()
        _whereAmI.value = p?.let { it.label.ifBlank { String.format(java.util.Locale.US, "%.4f, %.4f", it.lat, it.lon) } }
            ?: "No location yet. Check that Location is on and allowed for TruAgent."
    }

    val hasPlacesKey: Boolean get() = !container.settings.googlePlacesKey.isNullOrBlank()
    fun savePlacesKey(key: String?) {
        container.settings.googlePlacesKey = key
        toast(if (key.isNullOrBlank()) "Google Places key removed, using OpenStreetMap" else "Google Places key saved")
    }

    // Sign in with Google

    private val _googleAccounts = MutableStateFlow(container.settings.googleAccounts)
    val googleAccounts: StateFlow<List<String>> = _googleAccounts.asStateFlow()
    private val _googleStatus = MutableStateFlow<String?>(null)
    val googleStatus: StateFlow<String?> = _googleStatus.asStateFlow()
    private val _googleConsent = MutableStateFlow<Pair<String, android.content.Intent>?>(null)
    /** Google's approval screen to show for an account; the screen launches it and calls [connectGoogle] again. */
    val googleConsent: StateFlow<Pair<String, android.content.Intent>?> = _googleConsent.asStateFlow()

    fun connectGoogle(account: String) {
        val address = account.trim()
        if (address.isEmpty()) return
        _googleConsent.value = null
        _googleStatus.value = "Asking Google for access to $address…"
        viewModelScope.launch {
            try {
                val consent = container.googleAuth.authorize(address)
                if (consent != null) {
                    _googleConsent.value = address to consent
                    _googleStatus.value = "Approve access for $address on the Google screen."
                } else {
                    container.settings.googleAccounts = container.settings.googleAccounts + address
                    _googleAccounts.value = container.settings.googleAccounts
                    _googleStatus.value = "Connected $address: Gmail, Calendar and Drive."
                }
            } catch (e: Exception) {
                _googleStatus.value = e.message ?: "Google sign-in failed"
            }
        }
    }

    fun googleConsentFinished(account: String, approved: Boolean) {
        _googleConsent.value = null
        if (approved) connectGoogle(account) else _googleStatus.value = "Access for $account wasn't approved."
    }

    fun disconnectGoogle(account: String) {
        container.googleAuth.forget(account)
        container.settings.googleAccounts = container.settings.googleAccounts.filterNot { it.equals(account, ignoreCase = true) }
        _googleAccounts.value = container.settings.googleAccounts
        _googleStatus.value = "Removed $account. To fully revoke access, visit myaccount.google.com/permissions."
    }

    // Sign in with GitHub (device flow)

    sealed interface GitHubSignIn {
        data object Idle : GitHubSignIn
        data object Starting : GitHubSignIn
        data class Waiting(val userCode: String, val url: String) : GitHubSignIn
    }

    private val deviceFlow = dev.pocketagent.core.tools.GitHubDeviceFlow()
    private var githubJob: kotlinx.coroutines.Job? = null
    private val _githubSignIn = MutableStateFlow<GitHubSignIn>(GitHubSignIn.Idle)
    val githubSignIn: StateFlow<GitHubSignIn> = _githubSignIn.asStateFlow()
    val githubClientId: String get() = container.settings.githubClientId

    fun startGithubSignIn(clientId: String) {
        val id = clientId.trim()
        if (id.isEmpty()) {
            _github.value = "Enter your GitHub OAuth app's Client ID first."
            return
        }
        container.settings.githubClientId = id
        githubJob?.cancel()
        _githubSignIn.value = GitHubSignIn.Starting
        githubJob = viewModelScope.launch {
            try {
                val code = deviceFlow.start(id)
                _githubSignIn.value = GitHubSignIn.Waiting(code.userCode, code.verificationUri)
                _github.value = "Enter the code on GitHub, then come back."
                var interval = code.intervalSeconds.coerceAtLeast(5)
                val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L
                while (System.currentTimeMillis() < deadline) {
                    kotlinx.coroutines.delay(interval * 1000L)
                    when (val r = deviceFlow.poll(id, code.deviceCode)) {
                        is dev.pocketagent.core.tools.GitHubDeviceFlow.Poll.Token -> {
                            _githubSignIn.value = GitHubSignIn.Idle
                            container.settings.githubToken = r.token
                            testGithub()
                            return@launch
                        }
                        dev.pocketagent.core.tools.GitHubDeviceFlow.Poll.Pending -> Unit
                        is dev.pocketagent.core.tools.GitHubDeviceFlow.Poll.SlowDown -> interval = r.intervalSeconds
                        is dev.pocketagent.core.tools.GitHubDeviceFlow.Poll.Failed -> {
                            _github.value = r.message
                            _githubSignIn.value = GitHubSignIn.Idle
                            return@launch
                        }
                    }
                }
                _github.value = "The code expired. Tap Sign in with GitHub again."
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _github.value = e.message ?: "GitHub sign-in failed"
            }
            _githubSignIn.value = GitHubSignIn.Idle
        }
    }

    fun cancelGithubSignIn() {
        githubJob?.cancel()
        _githubSignIn.value = GitHubSignIn.Idle
        _github.value = null
    }

    private val _github = MutableStateFlow<String?>(null)
    /** "Connected as …" or an error, after saving or testing the GitHub token. */
    val githubStatus: StateFlow<String?> = _github.asStateFlow()
    val hasGithubToken: Boolean get() = container.github.hasToken()
    val builtApps: Set<String> get() = container.settings.builtApps

    // Telegram

    val telegramStatus: StateFlow<String> get() = container.telegram.status
    val telegramPairCode: String get() = container.telegram.pairingCode
    val hasTelegramToken: Boolean get() = !container.settings.telegramToken.isNullOrBlank()
    val telegramPaired: Boolean get() = container.settings.telegramChatId != null
    private val _telegramNote = MutableStateFlow<String?>(null)
    val telegramNote: StateFlow<String?> = _telegramNote.asStateFlow()

    fun saveTelegramToken(token: String) = viewModelScope.launch {
        val t = token.trim()
        _telegramNote.value = "Checking the token…"
        _telegramNote.value = try {
            val name = container.telegram.test(t)
            container.settings.telegramToken = t
            container.settings.telegramChatId = null
            container.settings.telegramOffset = 0L
            setTelegram(true)
            "Connected to $name. Now open it in Telegram and send: /start ${container.telegram.pairingCode}"
        } catch (e: Exception) {
            e.message ?: "That token didn't work"
        }
    }

    fun setTelegram(on: Boolean) {
        container.settings.update { it.copy(telegramEnabled = on) }
        if (on) {
            if (!dev.pocketagent.service.TelegramService.start(getApplication())) _telegramNote.value = "Open TruAgent and try again; Android blocked starting the bot in the background."
        } else {
            dev.pocketagent.service.TelegramService.stop(getApplication())
            container.telegram.stop()
        }
    }

    fun unpairTelegram() {
        container.settings.telegramChatId = null
        _telegramNote.value = "Unpaired. Send /start ${container.telegram.pairingCode} from the Telegram account you want to use."
    }

    fun removeTelegram() {
        setTelegram(false)
        container.settings.telegramToken = null
        container.settings.telegramChatId = null
        _telegramNote.value = "Bot token removed from this phone."
    }

    // Termux

    fun termuxInstalled(): Boolean = container.termux.isInstalled()
    fun termuxPermitted(): Boolean = container.termux.hasPermission()
    private val _termuxStatus = MutableStateFlow<String?>(null)
    val termuxStatus: StateFlow<String?> = _termuxStatus.asStateFlow()

    fun testTermux() = viewModelScope.launch {
        _termuxStatus.value = "Asking Termux…"
        val r = container.termux.run("echo \"Termux is connected: \$(uname -m), \$(node --version 2>/dev/null || echo no node)\"", 20_000)
        _termuxStatus.value = r.error ?: r.stdout.trim().ifBlank { "Termux answered with exit code ${r.exitCode}." }
    }

    fun saveGithubToken(token: String?) {
        container.settings.githubToken = token?.trim()
        if (token.isNullOrBlank()) {
            _github.value = "GitHub disconnected and the token removed from this phone."
        } else {
            testGithub()
        }
    }

    fun testGithub() = viewModelScope.launch {
        _github.value = "Checking the token…"
        _github.value = try {
            "Connected as ${container.github.whoAmI()}."
        } catch (e: Exception) {
            e.message ?: "GitHub check failed"
        }
    }

    val hasPollinationsToken: Boolean get() = !container.settings.pollinationsToken.isNullOrBlank()
    fun savePollinationsToken(token: String?) {
        container.settings.pollinationsToken = token
        toast(if (token.isNullOrBlank()) "Pollinations key removed" else "Pollinations key saved (encrypted, not included in backups)")
    }

    // endregion

    // region Growth

    val growth: StateFlow<Growth> = container.learner.growth
    val lessons: StateFlow<List<Lesson>> = container.learner.lessons
    fun deleteLesson(lesson: Lesson) = viewModelScope.launch(Dispatchers.IO) { container.learner.deleteLesson(lesson) }
    fun clearLessons() = viewModelScope.launch(Dispatchers.IO) { container.learner.clearLessons() }
    fun resetGrowth() = viewModelScope.launch(Dispatchers.IO) { container.learner.resetGrowth() }

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
        if (container.settings.current.telegramEnabled && !container.settings.telegramToken.isNullOrBlank()) {
            dev.pocketagent.service.TelegramService.start(getApplication())
        }
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

sealed interface CharacterState {
    data object Idle : CharacterState
    data class Drawing(val description: String) : CharacterState
    data class Preview(val file: File, val description: String) : CharacterState
    data class Failed(val message: String) : CharacterState
}

package dev.pocketagent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dev.pocketagent.agent.AgentRunner
import dev.pocketagent.agent.AgentSessionController
import dev.pocketagent.agent.ChangeCharacterTool
import dev.pocketagent.agent.ImagineTool
import dev.pocketagent.agent.CharacterGenerator
import dev.pocketagent.agent.ComputerSession
import dev.pocketagent.agent.DeviceLocation
import dev.pocketagent.agent.EmailClient
import dev.pocketagent.agent.EmailTool
import dev.pocketagent.core.tools.GitHubClient
import dev.pocketagent.core.tools.GitHubTool
import dev.pocketagent.core.tools.AppBuilderTool
import dev.pocketagent.core.tools.GoogleApi
import dev.pocketagent.core.tools.GoogleCalendarTool
import dev.pocketagent.core.tools.GoogleDriveTool
import dev.pocketagent.agent.GoogleAuth
import dev.pocketagent.agent.TermuxBridge
import dev.pocketagent.agent.TermuxTool
import dev.pocketagent.agent.LearnSkillTool
import dev.pocketagent.agent.Learner
import dev.pocketagent.agent.PhoneTool
import dev.pocketagent.agent.SharedFolderTool
import dev.pocketagent.core.agent.AgentMode
import dev.pocketagent.core.agent.PersonaManager
import dev.pocketagent.core.agent.Tool
import dev.pocketagent.core.agent.ToolRegistry
import dev.pocketagent.core.agent.ToolResult
import dev.pocketagent.core.llm.ProviderScheduler
import dev.pocketagent.core.llm.RemoteApiEngine
import dev.pocketagent.core.tools.ComputerTool
import dev.pocketagent.core.tools.CurrentTimeTool
import dev.pocketagent.core.tools.LocationTool
import dev.pocketagent.core.tools.PlacesTool
import dev.pocketagent.core.tools.WeatherTool
import dev.pocketagent.core.places.PlacesClient
import dev.pocketagent.core.tools.FetchPageTool
import dev.pocketagent.core.tools.RememberTool
import dev.pocketagent.core.tools.RunCodeTool
import dev.pocketagent.core.tools.SaveArtifactTool
import dev.pocketagent.core.tools.TrackGoalTool
import dev.pocketagent.core.tools.WebSearchTool
import dev.pocketagent.data.AppDatabase
import dev.pocketagent.data.ArtifactEntity
import dev.pocketagent.data.Backup
import dev.pocketagent.data.GoalEntity
import dev.pocketagent.data.SecureSettings
import dev.pocketagent.data.Workspace
import dev.pocketagent.feed.FeedGenerator
import dev.pocketagent.llm.LocalGgufEngine
import dev.pocketagent.llm.LocalModelState
import dev.pocketagent.llm.ModelDownloadManager
import dev.pocketagent.routines.RoutineScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

class PocketAgentApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        container = AppContainer(this)
        container.start()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_AGENT, getString(R.string.notif_channel_agent), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, getString(R.string.notif_channel_downloads), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ROUTINES, getString(R.string.notif_channel_routines), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val CHANNEL_AGENT = "agent"
        const val CHANNEL_DOWNLOADS = "downloads"
        const val CHANNEL_ROUTINES = "routines"
    }
}

/** Hides a network tool while Offline mode is on. */
private class OnlineOnlyTool(private val inner: Tool, private val offline: () -> Boolean) : Tool {
    override val name: String get() = inner.name
    override val description: String get() = inner.description
    override val argumentsSignature: String get() = inner.argumentsSignature
    override val modes: Set<AgentMode> get() = inner.modes
    override val timeoutMs: Long? get() = inner.timeoutMs
    override val keywords: List<String> get() = inner.keywords
    override fun isAvailable(): Boolean = !offline() && inner.isAvailable()
    override suspend fun execute(arguments: JsonObject): ToolResult =
        if (offline()) ToolResult.error("offline mode is on, network tools are disabled") else inner.execute(arguments)
}

/** Manual dependency graph; everything here lives as long as the process. */
class AppContainer(val app: PocketAgentApp) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SecureSettings(app)
    val database = AppDatabase.create(app)
    val workspace = Workspace(app)
    val localEngine = LocalGgufEngine(app)
    val remoteEngine = RemoteApiEngine(settings, settings.current.remoteConfig, appTitle = "TruAgent")
    val scheduler = ProviderScheduler(localEngine, remoteEngine, settings.current.preference)
    val downloads = ModelDownloadManager(app)

    val persona = PersonaManager(
        directory = workspace.personaDir,
        deviceDescription = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"
    )

    private val offline: () -> Boolean = { settings.current.offlineMode }

    val computer = ComputerSession(workspace)
    val characters = CharacterGenerator(settings)
    val videos = dev.pocketagent.agent.VideoMaker(settings)
    val voice = dev.pocketagent.agent.CloudVoice(settings, app.cacheDir)
    val learner = Learner(this)
    val location = DeviceLocation(app) { settings.current.shareLocation }
    val places = PlacesClient(googleKey = { settings.googlePlacesKey })
    val googleAuth = GoogleAuth(app, settings)
    val termux = TermuxBridge(app)
    val google = GoogleApi(googleAuth)
    val email = EmailClient(settings, googleAuth)
    val github = GitHubClient(token = { settings.githubToken })
    private val imperial: () -> Boolean = { DeviceLocation.prefersImperial() }

    val tools = ToolRegistry(
        listOf(
            OnlineOnlyTool(WebSearchTool({ settings.current.searchSettings }), offline),
            OnlineOnlyTool(FetchPageTool(), offline),
            CurrentTimeTool(),
            SharedFolderTool(app, workspace),
            PhoneTool(app) { settings.current.phoneControl },
            ComputerTool(computer.computer, computer::recordAgent),
            RunCodeTool(computer.sandbox, computer::recordAgent),
            LearnSkillTool(workspace, learner),
            LocationTool(location::current, places) { settings.current.shareLocation },
            OnlineOnlyTool(PlacesTool(places, location::current, imperial), offline),
            OnlineOnlyTool(WeatherTool(places, location::current, imperial), offline),
            OnlineOnlyTool(EmailTool(email) { settings.current.emailCanOrganize }, offline),
            OnlineOnlyTool(GitHubTool(github) { settings.current.githubCanWrite }, offline),
            OnlineOnlyTool(
                AppBuilderTool(github, { settings.builtApps }, { settings.builtApps = settings.builtApps + it }) { settings.current.appBuilder },
                offline
            ),
            OnlineOnlyTool(GoogleCalendarTool(google, { settings.current.calendarCanWrite }), offline),
            OnlineOnlyTool(GoogleDriveTool(google), offline),
            TermuxTool(termux) { settings.current.termuxEnabled },
            OnlineOnlyTool(ChangeCharacterTool(characters, workspace, settings), offline),
            OnlineOnlyTool(ImagineTool(characters, workspace, settings) { session.turnImage }, offline),
            OnlineOnlyTool(dev.pocketagent.agent.MakeVideoTool(videos, workspace, settings) { session.turnImage }, offline),
            SaveArtifactTool { title, content, ext -> saveArtifact(title, content, ext) },
            RememberTool { fact -> withContext(Dispatchers.IO) { workspace.appendMemory(fact) } },
            TrackGoalTool { title, note, category ->
                database.content().insertGoal(
                    GoalEntity(title = title, note = note, category = category, createdAt = System.currentTimeMillis())
                )
            }
        )
    )

    val runner = AgentRunner(this)
    val feed = FeedGenerator(this)
    val routines = RoutineScheduler(app)
    val session = AgentSessionController(this)
    val telegram = dev.pocketagent.agent.TelegramBot(this)
    val backup = Backup(app, database, workspace, settings)

    init {
        appScope.launch {
            settings.settings.collect { s ->
                remoteEngine.config = s.remoteConfig
                scheduler.preference = s.preference
            }
        }
    }

    fun start() {
        appScope.launch(Dispatchers.IO) {
            workspace.ensureDefaultSkills()
            // 2.0.1 could save empty facts like "For the next time I ask you"; drop them.
            runCatching {
                val memory = workspace.readMemory()
                val vague = Regex("^- (?:for (?:the )?next time|next time|for later|for the future)[^\\n]*$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
                if (vague.containsMatchIn(memory)) workspace.writeMemory(memory.replace(Regex(vague.pattern + "\\n?", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)), ""))
            }
            try {
                routines.rescheduleAll()
            } catch (e: Exception) {
                workspace.log("E", "routine scheduling failed: ${e.message}")
            }
        }
        autoLoadLastModel()
    }

    suspend fun saveArtifact(title: String, content: String, ext: String): String = withContext(Dispatchers.IO) {
        val fileName = workspace.safeFileName(title, ext)
        val file = File(workspace.artifactsDir, fileName)
        file.writeText(content)
        database.content().insertArtifact(
            ArtifactEntity(title = title, fileName = fileName, format = ext, sizeBytes = file.length(), createdAt = System.currentTimeMillis())
        )
        workspace.log("I", "artifact saved: $fileName")
        fileName
    }

    fun autoLoadLastModel() {
        val s = settings.current
        val path = s.lastModelPath ?: return
        if (!s.autoLoadModel || !File(path).isFile) return
        appScope.launch { localEngine.load(path, s.contextSize, s.threads) }
    }

    /** Used by background jobs: loads the last on-device model if routing needs it and nothing is loaded. */
    suspend fun ensureModelLoaded() {
        if (localEngine.state.value is LocalModelState.Ready || localEngine.state.value is LocalModelState.Loading) return
        val s = settings.current
        if (s.preference == dev.pocketagent.core.llm.ProviderPreference.REMOTE_ONLY) return
        val path = s.lastModelPath ?: return
        if (File(path).isFile) localEngine.load(path, s.contextSize, s.threads)
    }
}

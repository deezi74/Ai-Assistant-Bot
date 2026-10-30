package dev.pocketagent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dev.pocketagent.agent.AgentSessionController
import dev.pocketagent.core.agent.PersonaManager
import dev.pocketagent.core.agent.ToolRegistry
import dev.pocketagent.core.llm.ProviderScheduler
import dev.pocketagent.core.llm.RemoteApiEngine
import dev.pocketagent.core.tools.CurrentTimeTool
import dev.pocketagent.core.tools.FetchPageTool
import dev.pocketagent.core.tools.WebSearchTool
import dev.pocketagent.data.AppDatabase
import dev.pocketagent.data.SecureSettings
import dev.pocketagent.llm.LocalGgufEngine
import dev.pocketagent.llm.ModelDownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class PocketAgentApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        container = AppContainer(this)
        container.autoLoadLastModel()
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
    }

    companion object {
        const val CHANNEL_AGENT = "agent"
        const val CHANNEL_DOWNLOADS = "downloads"
    }
}

/** Manual dependency graph; everything here lives as long as the process. */
class AppContainer(val app: PocketAgentApp) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SecureSettings(app)
    val database = AppDatabase.create(app)
    val localEngine = LocalGgufEngine(app)
    val remoteEngine = RemoteApiEngine(settings, settings.current.remoteConfig, appTitle = "PocketAgent")
    val scheduler = ProviderScheduler(localEngine, remoteEngine, settings.current.preference)
    val downloads = ModelDownloadManager(app)

    val persona = PersonaManager(
        directory = File(app.filesDir, "persona"),
        deviceDescription = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"
    )

    val tools = ToolRegistry(
        listOf(
            WebSearchTool({ settings.current.searchSettings }),
            FetchPageTool(),
            CurrentTimeTool()
        )
    )

    val session = AgentSessionController(this)

    init {
        appScope.launch {
            settings.settings.collect { s ->
                remoteEngine.config = s.remoteConfig
                scheduler.preference = s.preference
            }
        }
    }

    fun autoLoadLastModel() {
        val s = settings.current
        val path = s.lastModelPath ?: return
        if (!s.autoLoadModel || !File(path).isFile) return
        appScope.launch { localEngine.load(path, s.contextSize, s.threads) }
    }
}

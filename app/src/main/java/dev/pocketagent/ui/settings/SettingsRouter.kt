package dev.pocketagent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Toll
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketagent.BuildConfig
import dev.pocketagent.llm.LocalModelState
import dev.pocketagent.ui.AppViewModel
import dev.pocketagent.ui.SectionTitle
import dev.pocketagent.ui.SettingRow
import dev.pocketagent.ui.SubScreen
import dev.pocketagent.ui.openUrl
import dev.pocketagent.ui.theme.Palette

const val FEEDBACK_URL = "https://github.com/deezi74/Ai-Assistant-Bot/issues"

@Composable
fun SettingsRouter(viewModel: AppViewModel, route: String, onRestartNeeded: () -> Unit) {
    val back: () -> Unit = { viewModel.pop() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
    ) {
        when (route) {
            "settings" -> SettingsHome(viewModel)
            "models" -> ModelsScreen(viewModel, back)
            "providers" -> ProvidersScreen(viewModel, back)
            "usage" -> TokenUsageScreen(viewModel, back)
            "soul" -> SoulScreen(viewModel, back)
            "avatar" -> AvatarScreen(viewModel, back)
            "memory" -> MemoryScreen(viewModel, back)
            "files" -> SystemFilesScreen(viewModel, back)
            "skills" -> SkillsScreen(viewModel, back)
            "connectors" -> ConnectorsScreen(viewModel, back)
            "hands", "phone" -> PhoneControlsScreen(viewModel, back)
            "computers", "computer" -> ComputerScreen(viewModel, back)
            "coding", "code" -> CodeScreen(viewModel, back)
            "growth" -> GrowthScreen(viewModel, back)
            "email" -> EmailConnectorScreen(viewModel, back)
            "location" -> LocationConnectorScreen(viewModel, back)
            "github" -> GitHubConnectorScreen(viewModel, back)
            "google" -> GoogleConnectorScreen(viewModel, back)
            "termux" -> TermuxConnectorScreen(viewModel, back)
            "telegram" -> TelegramConnectorScreen(viewModel, back)
            "media" -> MediaStudioScreen(viewModel, back)
            "env" -> EnvVarsScreen(viewModel, back)
            "permissions" -> PermissionsScreen(viewModel, back)
            "background" -> BackgroundScreen(viewModel, back)
            "storage" -> StorageScreen(viewModel, back)
            "shared" -> SharedFoldersScreen(viewModel, back)
            "mounts" -> MountsScreen(viewModel, back)
            "backup" -> BackupScreen(viewModel, back, onRestartNeeded)
            "appearance" -> AppearanceScreen(viewModel, back)
            "data" -> DataControlsScreen(viewModel, back)
            "network" -> NetworkScreen(viewModel, back)
            "logs" -> LogsScreen(viewModel, back)
            "lock" -> AppLockScreen(viewModel, back)
            "assistant" -> DefaultAssistantScreen(viewModel, back)
            "about" -> AboutScreen(viewModel, back)
            "privacy" -> PrivacyScreen(back)
            else -> SettingsHome(viewModel)
        }
    }
}

@Composable
private fun SettingsHome(viewModel: AppViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val local by viewModel.localState.collectAsStateWithLifecycle()
    val mounts by viewModel.mounts.collectAsStateWithLifecycle()
    val env by viewModel.env.collectAsStateWithLifecycle()
    val growth by viewModel.growth.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val go: (String) -> Unit = { viewModel.push(it) }

    SubScreen("Settings", onBack = { viewModel.pop() }) {
        SectionTitle("Assistant")
        SettingRow(Icons.Filled.AccountCircle, "Avatar & name", settings.assistantName, onClick = { go("avatar") })
        SettingRow(Icons.Filled.Psychology, "Soul", "Personality and voice (soul.md)", onClick = { go("soul") })
        SettingRow(Icons.Filled.Memory, "Memory", "What ${settings.assistantName} remembers about you", onClick = { go("memory") })
        SettingRow(Icons.Filled.Extension, "Skills", "Reusable instructions", onClick = { go("skills") })
        SettingRow(Icons.Filled.TrendingUp, "Growth", "Level ${growth.level} · ${growth.title}" + if (settings.autoLearn) ", learning" else ", learning off", onClick = { go("growth") })
        SettingRow(Icons.Filled.Description, "System files", "soul.md, memory.md, skills", onClick = { go("files") })

        SectionTitle("Models")
        SettingRow(
            Icons.Filled.SmartToy, "Models",
            when (val s = local) {
                is LocalModelState.Ready -> "On-device: ${s.fileName}"
                is LocalModelState.Loading -> "Loading ${s.fileName}"
                else -> "Download or import a GGUF model"
            },
            onClick = { go("models") }
        )
        SettingRow(Icons.Filled.Hub, "Manage providers", "${settings.remoteKind.label} · ${settings.remoteModel}", onClick = { go("providers") })
        SettingRow(Icons.Filled.Toll, "Token usage", "Per day and provider", onClick = { go("usage") })

        SectionTitle("Abilities")
        SettingRow(Icons.Filled.Cable, "Connectors", "Email, location & places, web search, providers", onClick = { go("connectors") })
        SettingRow(Icons.Filled.PanTool, "Phone controls", if (settings.phoneControl) "Flashlight, volume, alarms, apps" else "Off", onClick = { go("phone") })
        SettingRow(Icons.Filled.Computer, "Computer", "${settings.assistantName}'s own computer on this phone", onClick = { go("computer") })
        SettingRow(Icons.Filled.Code, "Code", "Projects it builds and runs", onClick = { go("code") })
        SettingRow(Icons.Filled.Key, "Environment variables", if (env.isEmpty()) "None" else "${env.size} set", onClick = { go("env") })

        SectionTitle("Device")
        SettingRow(Icons.Filled.Shield, "Permissions", "Notifications, battery", onClick = { go("permissions") })
        SettingRow(Icons.Filled.BatteryChargingFull, "Background & notifications", "Keep working while the screen is off", onClick = { go("background") })
        SettingRow(Icons.Filled.Storage, "Storage", "Models, Library, chats", onClick = { go("storage") })
        SettingRow(Icons.Filled.Folder, "Shared folders", "Where TruAgent keeps files", onClick = { go("shared") })
        SettingRow(Icons.Filled.FolderShared, "Mount external folders", if (mounts.isEmpty()) "None mounted" else "${mounts.size} mounted", onClick = { go("mounts") })
        SettingRow(Icons.Filled.Backup, "Backup & restore", "Export everything except keys", onClick = { go("backup") })

        SectionTitle("App")
        SettingRow(Icons.Filled.Palette, "Appearance", "Colours, background, text size", onClick = { go("appearance") })
        SettingRow(Icons.Filled.DataUsage, "Data controls", "Delete chats, feed, memory", onClick = { go("data") })
        SettingRow(Icons.Filled.Wifi, "Network", if (settings.offlineMode) "Offline mode on" else "Search, Wi-Fi only downloads", onClick = { go("network") })
        SettingRow(Icons.Filled.Terminal, "Logs", "What the agent did", onClick = { go("logs") })
        SettingRow(Icons.Filled.Lock, "App lock", if (settings.appLock) "On" else "Off", onClick = { go("lock") })
        SettingRow(Icons.Filled.Apps, "Set as default assistant", "Open with the assist gesture", onClick = { go("assistant") })

        SectionTitle("About")
        SettingRow(Icons.Filled.Info, "About TruAgent", onClick = { go("about") })
        SettingRow(Icons.Filled.Policy, "Privacy policy", onClick = { go("privacy") })
        SettingRow(Icons.AutoMirrored.Filled.Help, "Send feedback", "Opens GitHub issues", onClick = { openUrl(context, FEEDBACK_URL) })
        SettingRow(Icons.Filled.Tag, "Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }
}

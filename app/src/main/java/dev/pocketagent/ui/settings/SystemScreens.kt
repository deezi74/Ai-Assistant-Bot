package dev.pocketagent.ui.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketagent.BuildConfig
import dev.pocketagent.core.tools.SearchBackend
import dev.pocketagent.ui.AppTextField
import dev.pocketagent.ui.AppViewModel
import dev.pocketagent.ui.ConfirmDialog
import dev.pocketagent.ui.Hint
import dev.pocketagent.ui.LabeledSlider
import dev.pocketagent.ui.SectionTitle
import dev.pocketagent.ui.SubScreen
import dev.pocketagent.ui.SurfaceCard
import dev.pocketagent.ui.SwitchRow
import dev.pocketagent.ui.formatBytes
import dev.pocketagent.ui.openUrl
import dev.pocketagent.ui.theme.Accents
import dev.pocketagent.ui.theme.Backgrounds
import dev.pocketagent.ui.theme.TextColors
import dev.pocketagent.ui.theme.Mono
import dev.pocketagent.ui.theme.Palette
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bumps whenever the screen resumes, so permission checks refresh after returning from system settings. */
@Composable
private fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return tick
}

private fun startSafely(context: Context, intent: Intent, fallback: Intent = Intent(Settings.ACTION_SETTINGS)) {
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        context.startActivity(fallback)
    }
}

@Composable
fun TokenUsageScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    val totalIn = usage.sumOf { it.promptTokens }
    val totalOut = usage.sumOf { it.completionTokens }
    SubScreen("Token usage", onBack, actions = {
        if (usage.isNotEmpty()) IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.DeleteSweep, "Reset") }
    }) {
        SurfaceCard {
            Text("All time", style = MaterialTheme.typography.titleMedium)
            Text("${"%,d".format(totalIn + totalOut)} tokens", style = MaterialTheme.typography.headlineSmall, color = Palette.Accent)
            Hint("${"%,d".format(totalIn)} in · ${"%,d".format(totalOut)} out · ${usage.sumOf { it.requests }} requests")
        }
        Hint("Counted from what each provider reports. On-device generations count prompt and reply tokens from llama.cpp.")
        usage.groupBy { it.day }.forEach { (day, rows) ->
            SectionTitle(day)
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.provider, style = MaterialTheme.typography.bodyLarge)
                        Hint("${r.requests} requests")
                    }
                    Text("${"%,d".format(r.promptTokens)} / ${"%,d".format(r.completionTokens)}", style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted)
                }
            }
        }
        if (usage.isEmpty()) Hint("Nothing recorded yet.")
    }
    if (confirm) ConfirmDialog("Reset usage?", "All token counts are cleared.", "Reset", onConfirm = { viewModel.clearUsage() }, onDismiss = { confirm = false })
}

@Composable
fun ConnectorsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val emailAccounts by viewModel.emailAccounts.collectAsStateWithLifecycle()
    val mounts by viewModel.mounts.collectAsStateWithLifecycle()
    SubScreen("Connectors", onBack) {
        Hint("Services the assistant can reach. Everything is off-device only when you use it.")
        ConnectorCard(
            "Web search",
            if (settings.offlineMode) "Paused by offline mode" else if (settings.searchBackend == SearchBackend.SEARXNG) "SearXNG · ${settings.searxngUrl.ifBlank { "no URL set" }}" else "DuckDuckGo",
            connected = !settings.offlineMode && (settings.searchBackend == SearchBackend.DUCKDUCKGO || settings.searxngUrl.isNotBlank()),
            action = "Configure"
        ) { viewModel.push("network") }
        ConnectorCard("Web pages", "Reads links you or the assistant open", connected = !settings.offlineMode, action = null) {}
        ConnectorCard(
            "Model provider", "${settings.remoteKind.label} · ${settings.remoteModel}",
            connected = viewModel.hasToken(settings.remoteKind), action = "Manage"
        ) { viewModel.push("providers") }
        ConnectorCard(
            "Hugging Face", if (viewModel.hasHfToken) "Token saved, gated models allowed" else "Public models only",
            connected = true, action = "Models"
        ) { viewModel.push("models") }
        ConnectorCard(
            "Shared folders", if (mounts.isEmpty()) "No folders mounted" else "${mounts.size} folder(s), read-only",
            connected = mounts.isNotEmpty(), action = "Mount"
        ) { viewModel.push("mounts") }
        ConnectorCard(
            "Email",
            when (emailAccounts.size) {
                0 -> "Not connected"
                1 -> "${emailAccounts[0].user} · ${if (settings.emailCanOrganize) "read and organize" else "read only"}"
                else -> "${emailAccounts.size} accounts · ${if (settings.emailCanOrganize) "read and organize" else "read only"}"
            },
            connected = emailAccounts.isNotEmpty(), action = if (emailAccounts.isNotEmpty()) "Manage" else "Connect"
        ) { viewModel.push("email") }
        ConnectorCard(
            "Location & places",
            if (settings.shareLocation) "On · nearby places, distances, opening hours, weather" else "Off",
            connected = settings.shareLocation, action = "Set up"
        ) { viewModel.push("location") }
        ConnectorCard(
            "GitHub",
            if (viewModel.hasGithubToken) (if (settings.githubCanWrite) "Connected · read, open issues and comment" else "Connected · read only") else "Not connected",
            connected = viewModel.hasGithubToken, action = if (viewModel.hasGithubToken) "Manage" else "Connect"
        ) { viewModel.push("github") }
        Hint("Calendar and contacts are not connected in this build.")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmailConnectorScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val accounts by viewModel.emailAccounts.collectAsStateWithLifecycle()
    val status by viewModel.emailStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var presetId by remember { mutableStateOf(viewModel.emailPresets.first().id) }
    val preset = viewModel.emailPresets.first { it.id == presetId }
    var host by remember { mutableStateOf(preset.host) }
    var port by remember { mutableStateOf("993") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<String?>(null) }

    SubScreen("Email", onBack) {
        Hint("${settings.assistantName} reads your inboxes over IMAP to summarize them, find messages, and keep them tidy. Ask things like \"what's new in my email?\" or \"archive the newsletters in my work inbox\". With several accounts it checks all of them unless you name one.")
        SectionTitle("Connected (${accounts.size})")
        if (accounts.isEmpty()) Hint("No accounts yet.")
        accounts.forEach { acc ->
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(acc.user, style = MaterialTheme.typography.titleMedium)
                        Hint(acc.host)
                    }
                    IconButton(onClick = { removing = acc.user }) { Icon(Icons.Filled.DeleteOutline, "Remove", tint = Palette.TextMuted) }
                }
            }
        }

        SectionTitle(if (accounts.isEmpty()) "Add an account" else "Add another account")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            viewModel.emailPresets.forEach { p ->
                val selected = presetId == p.id
                Text(
                    p.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) Color.Black else Palette.Text,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (selected) Palette.Accent else Palette.SurfaceHigh)
                        .clickable {
                            presetId = p.id
                            host = p.host
                            port = p.port.toString()
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }
        }
        Hint(preset.help)
        if (preset.id == "gmail") {
            OutlinedButton(onClick = { openUrl(context, "https://myaccount.google.com/apppasswords") }) { Text("Create a Gmail app password") }
        }
        AppTextField(user, { user = it.trim() }, "Email address")
        SecretField(password, { password = it }, "App password")
        if (preset.id == "other") {
            AppTextField(host, { host = it.trim() }, "IMAP server, e.g. imap.example.com")
            AppTextField(port, { port = it.filter(Char::isDigit).take(5) }, "Port (usually 993)")
        }
        Button(
            onClick = {
                viewModel.connectEmail(host, port.toIntOrNull() ?: 993, user, password)
                password = ""
                user = ""
            },
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black)
        ) { Text("Connect & test") }
        status?.let { Hint(it, if (it.startsWith("Connected")) Palette.Success else Palette.TextMuted) }
        Hint("To replace an app password, add the same address again.")

        SectionTitle("Permissions")
        SwitchRow(
            "Let it organize",
            "Archive, mark as read and move to Trash when you ask. Mail in Trash can be restored for about 30 days. Nothing is ever deleted permanently.",
            settings.emailCanOrganize
        ) { c -> viewModel.updateSettings { it.copy(emailCanOrganize = c) } }
        Hint("App passwords are stored encrypted on this phone and never included in backups. Email text is treated as untrusted: instructions hidden inside an email are ignored. It never sends email.")
    }
    removing?.let { address ->
        ConfirmDialog("Remove $address?", "Its app password is deleted from this phone.", "Remove", { viewModel.disconnectEmail(address) }, { removing = null })
    }
}

@Composable
fun LocationConnectorScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val where by viewModel.whereAmI.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val tick = rememberResumeTick()
    val granted = remember(tick) { viewModel.hasLocationPermission() }
    val locationOn = remember(tick) { viewModel.isLocationOn() }
    var placesKey by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) viewModel.updateSettings { it.copy(shareLocation = true) }
    }
    val askPermission = {
        launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    SubScreen("Location & places", onBack) {
        Hint("With your location ${settings.assistantName} can brief you on the day with local weather, tell you how far something is, and whether a business or restaurant is open right now.")
        SwitchRow(
            "Share my location with ${settings.assistantName}",
            "Read only when a question needs it, never in the background or sent anywhere else",
            settings.shareLocation && granted
        ) { on ->
            if (on && !granted) askPermission() else viewModel.updateSettings { it.copy(shareLocation = on) }
        }
        PermissionCard("Location permission", "Precise or approximate", granted) { askPermission() }
        if (!locationOn) {
            PermissionCard("Phone location is off", "Turn on Location in quick settings", false) {
                startSafely(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        }
        if (settings.shareLocation && granted) {
            OutlinedButton(onClick = { viewModel.testLocation() }) { Text("Where am I?") }
            where?.let { Hint(it) }
        }

        SectionTitle("Places data")
        Hint("Free by default: OpenStreetMap for places and opening hours, OSRM for drive times, Open-Meteo for weather. Hours in OpenStreetMap can be missing or out of date.")
        Hint(
            if (viewModel.hasPlacesKey) "A Google Places key is saved: live open/closed status, ratings and better coverage."
            else "Optional: add a Google Places API key (Places API (New)) for live open/closed status and ratings. Google bills your account per search."
        )
        SecretField(placesKey, { placesKey = it }, if (viewModel.hasPlacesKey) "Google Places key (saved)" else "Google Places API key (optional)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.savePlacesKey(placesKey); placesKey = "" }, enabled = placesKey.isNotBlank()) { Text("Save key") }
            if (viewModel.hasPlacesKey) OutlinedButton(onClick = { viewModel.savePlacesKey(null) }) { Text("Remove key") }
        }

        SectionTitle("Try saying")
        listOf(
            "Brief me on my day",
            "Is the closest pharmacy open right now?",
            "How far is the airport?",
            "Find a pizza place near me that's open",
            "What's the weather this afternoon?"
        ).forEach { example ->
            SurfaceCard(onClick = { viewModel.prefill(example) }) { Text(example) }
        }
    }
}

@Composable
fun GitHubConnectorScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val status by viewModel.githubStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var token by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf(false) }
    val connected = remember(status) { viewModel.hasGithubToken }

    SubScreen("GitHub", onBack) {
        Hint("${settings.assistantName} can check your repos, issues, pull requests and notifications, read files and search code. Ask things like \"what's new on GitHub?\" or \"summarize the open issues in owner/repo\".")
        SectionTitle(if (connected) "Connected" else "Connect")
        Hint("Create a fine-grained personal access token. For reading, give it Contents, Issues, Pull requests and Metadata read access on the repos you want. Add Issues write access only if you turn on writing below. Notifications need a classic token with the notifications scope.")
        OutlinedButton(onClick = { openUrl(context, "https://github.com/settings/personal-access-tokens/new") }) { Text("Create a token on GitHub") }
        SecretField(token, { token = it.trim() }, if (connected) "Personal access token (saved)" else "Personal access token")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.saveGithubToken(token); token = "" },
                enabled = token.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black)
            ) { Text("Save & test") }
            if (connected) {
                OutlinedButton(onClick = { viewModel.testGithub() }) { Text("Test") }
                OutlinedButton(onClick = { removing = true }) { Text("Disconnect") }
            }
        }
        status?.let { Hint(it, if (it.startsWith("Connected")) Palette.Success else Palette.TextMuted) }

        SectionTitle("Permissions")
        SwitchRow(
            "Let it open issues and comment",
            "Only when you ask. It never pushes code, merges, closes or deletes anything.",
            settings.githubCanWrite
        ) { c -> viewModel.updateSettings { it.copy(githubCanWrite = c) } }
        Hint("The token is stored encrypted on this phone and never included in backups. Issue and PR text is treated as untrusted: instructions hidden inside it are ignored.")

        SectionTitle("Try saying")
        listOf(
            "What's new on GitHub?",
            "List my repos",
            "Show the open pull requests in owner/repo",
            "Read the README in owner/repo"
        ).forEach { example ->
            SurfaceCard(onClick = { viewModel.prefill(example) }) { Text(example) }
        }
    }
    if (removing) {
        ConfirmDialog("Disconnect GitHub?", "The token is deleted from this phone. You can also revoke it on GitHub.", "Disconnect", { viewModel.saveGithubToken(null) }, { removing = false })
    }
}

@Composable
private fun ConnectorCard(title: String, detail: String, connected: Boolean, action: String?, onClick: () -> Unit) {
    SurfaceCard(onClick = if (action != null) onClick else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (connected) Palette.Success else Palette.Outline))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Hint(detail)
            }
            if (action != null) Text(action, style = MaterialTheme.typography.labelSmall, color = Palette.Accent)
        }
    }
}

@Composable
fun EnvVarsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val env by viewModel.env.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    SubScreen("Environment variables", onBack) {
        Hint("Values the assistant can reference as \$NAME in tool arguments, for example a SearXNG token or a city. Stored encrypted and never sent unless a tool uses them.")
        env.toSortedMap().forEach { (k, v) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("\$$k", style = MaterialTheme.typography.bodyLarge.copy(fontFamily = Mono))
                    Hint(if (v.length <= 4) "••••" else v.take(2) + "••••" + v.takeLast(2))
                }
                IconButton(onClick = { viewModel.removeEnv(k) }) { Icon(Icons.Filled.DeleteOutline, "Remove", tint = Palette.TextMuted) }
            }
        }
        SectionTitle("Add")
        AppTextField(name, { name = it }, "Name (A-Z, 0-9, _)")
        SecretField(value, { value = it }, "Value")
        Button(enabled = name.isNotBlank(), onClick = {
            viewModel.putEnv(name, value)
            name = ""
            value = ""
        }) { Text("Add variable") }
    }
}

@Composable
fun PermissionsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val notificationsOk = remember(tick) {
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val batteryOk = remember(tick) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    SubScreen("Permissions", onBack) {
        Hint("TruAgent asks for as little as possible. No contacts, microphone recording or accessibility access. Location is optional, in Connectors.")
        PermissionCard("Notifications", "Routine results and long-running chats", notificationsOk) {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        PermissionCard("Unrestricted battery", "Keeps local generation and routines from being cut off", batteryOk) {
            startSafely(
                context,
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
        }
        PermissionCard("Internet", "Web search, providers, model downloads", true) {}
        Hint("Voice input uses your phone's speech recognizer, so the microphone permission belongs to that app, not TruAgent.")
        OutlinedButton(onClick = {
            startSafely(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }) { Text("Open app info") }
    }
}

@Composable
private fun PermissionCard(title: String, detail: String, granted: Boolean, onGrant: () -> Unit) {
    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Hint(detail)
            }
            if (granted) {
                Text("Allowed", style = MaterialTheme.typography.labelSmall, color = Palette.Success)
            } else {
                Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black)) { Text("Allow") }
            }
        }
    }
}

@Composable
fun BackgroundScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SubScreen("Background & notifications", onBack) {
        SwitchRow(
            "Keep working in the background",
            "Shows a notification while the assistant is answering so Android doesn't stop it",
            settings.keepAwake
        ) { c -> viewModel.updateSettings { it.copy(keepAwake = c) } }
        SwitchRow("Show agent terminal", "The floating panel with thoughts and tool calls", settings.showTerminal) { c ->
            viewModel.updateSettings { it.copy(showTerminal = c) }
        }
        Hint("Routines run at their scheduled time even when the app is closed. Results arrive as notifications and in the Library.")
        OutlinedButton(onClick = {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            startSafely(context, intent)
        }) { Text("Notification settings") }
        OutlinedButton(onClick = { viewModel.push("permissions") }) { Text("Battery permission") }
    }
}

@Composable
fun StorageScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val report by viewModel.storage.collectAsStateWithLifecycle()
    var confirmMedia by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refreshStorage() }
    SubScreen("Storage", onBack) {
        SurfaceCard {
            Text("Used by TruAgent", style = MaterialTheme.typography.titleMedium)
            Text(
                formatBytes(report.modelsBytes + report.artifactsBytes + report.mediaBytes + report.databaseBytes + report.personaBytes),
                style = MaterialTheme.typography.headlineSmall,
                color = Palette.Accent
            )
            Hint("${formatBytes(report.freeBytes)} free on this device")
        }
        StorageLine("Models", report.modelsBytes, "Manage") { viewModel.push("models") }
        StorageLine("Library artifacts", report.artifactsBytes, null) {}
        StorageLine("Media", report.mediaBytes, "Clear") { confirmMedia = true }
        StorageLine("Chats & data", report.databaseBytes, "Data controls") { viewModel.push("data") }
        StorageLine("Soul, memory, skills, avatar", report.personaBytes, null) {}
    }
    if (confirmMedia) ConfirmDialog("Clear all media?", "Images in Library ▸ Media are deleted.", "Clear", onConfirm = { viewModel.clearMediaFiles() }, onDismiss = { confirmMedia = false })
}

@Composable
private fun StorageLine(label: String, bytes: Long, action: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Hint(formatBytes(bytes))
        }
        if (action != null) Text(action, color = Palette.Accent, style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable(onClick = onAction).padding(8.dp))
    }
}

@Composable
fun SharedFoldersScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    SubScreen("Shared folders", onBack) {
        Hint("Where TruAgent keeps files. Library and media live in app storage that a file manager or a computer over USB can reach.")
        listOf(
            "Library artifacts" to viewModel.workspace.artifactsDir,
            "Media" to viewModel.workspace.mediaDir,
            "Models (.gguf)" to File(context.getExternalFilesDir(null), "models")
        ).forEach { (label, dir) ->
            SurfaceCard {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(dir.absolutePath, style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted)
                Text(
                    "Copy path",
                    color = Palette.Accent,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clickable { copy(context, dir.absolutePath); viewModel.toast("Path copied") }
                )
            }
        }
        Hint("Tip: adb push model.gguf to the models folder, then rescan in Settings ▸ Models.")
    }
}

@Composable
fun MountsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val mounts by viewModel.mounts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.addMount(uri)
    }
    SubScreen("Mount external folders", onBack) {
        Hint("Give the assistant read-only access to a folder, for example Documents or a notes folder. It can list and read text files there with the shared_files tool. Nothing is uploaded unless you use a cloud provider.")
        Button(onClick = { picker.launch(null) }, colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black)) {
            Text("Mount a folder")
        }
        mounts.forEach { uri ->
            val doc = remember(uri) { DocumentFile.fromTreeUri(context, Uri.parse(uri)) }
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(doc?.name ?: "Folder", style = MaterialTheme.typography.titleMedium)
                        Text(Uri.decode(uri).substringAfterLast(':'), style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted, maxLines = 2)
                    }
                    IconButton(onClick = { viewModel.removeMount(uri) }) { Icon(Icons.Filled.DeleteOutline, "Unmount", tint = Palette.TextMuted) }
                }
            }
        }
    }
}

@Composable
fun BackupScreen(viewModel: AppViewModel, onBack: () -> Unit, onRestartNeeded: () -> Unit) {
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.exportBackup(uri)
    }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pendingRestore = uri }
    SubScreen("Backup & restore", onBack) {
        Hint("A backup is one .zip with your chats, feed, goals, routines, Library, media, soul, memory, skills, avatar and settings. API keys, tokens and environment variables are never included. Downloaded models are not included either.")
        Button(onClick = {
            exporter.launch("pocketagent-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".zip")
        }, colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black)) { Text("Export backup") }
        OutlinedButton(onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Restore from backup") }
    }
    pendingRestore?.let { uri ->
        ConfirmDialog(
            "Restore this backup?",
            "Current chats and files are replaced by the backup. TruAgent restarts when it's done.",
            "Restore",
            onConfirm = { viewModel.restoreBackup(uri, onRestartNeeded) },
            onDismiss = { pendingRestore = null }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SubScreen("Appearance", onBack) {
        SectionTitle("Accent colour")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Accents.forEach { a ->
                val selected = settings.accent == a.id
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { viewModel.updateSettings { it.copy(accent = a.id) } }) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(a.color)
                            .border(3.dp, if (selected) Palette.Text else Color.Transparent, CircleShape)
                    )
                    Text(a.label, style = MaterialTheme.typography.bodySmall, color = if (selected) Palette.Text else Palette.TextMuted)
                }
            }
        }
        SectionTitle("Background")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Backgrounds.forEach { b ->
                val selected = settings.background == b.id
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { viewModel.updateSettings { it.copy(background = b.id) } }) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(b.background)
                            .border(if (selected) 3.dp else 1.dp, if (selected) Palette.Accent else Palette.Outline, CircleShape)
                    )
                    Text(b.label, style = MaterialTheme.typography.bodySmall, color = if (selected) Palette.Text else Palette.TextMuted)
                }
            }
        }
        SectionTitle("Text colour")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextColors.forEach { t ->
                val selected = settings.textColor == t.id
                val swatch = t.color ?: if (Palette.IsLight) Color(0xFF15171B) else Color(0xFFECEEF1)
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { viewModel.updateSettings { it.copy(textColor = t.id) } }) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Palette.Surface)
                            .border(if (selected) 3.dp else 1.dp, if (selected) Palette.Accent else Palette.Outline, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Aa", color = swatch, style = MaterialTheme.typography.titleMedium)
                    }
                    Text(t.label, style = MaterialTheme.typography.bodySmall, color = if (selected) Palette.Text else Palette.TextMuted)
                }
            }
        }
        SectionTitle("Preview")
        SurfaceCard {
            Text("${settings.assistantName}", style = MaterialTheme.typography.titleMedium)
            Text("This is how messages, labels and buttons will look.", style = MaterialTheme.typography.bodyLarge)
            Hint("Secondary text like timestamps and hints looks like this.")
            Text("Accent", color = Palette.Accent, style = MaterialTheme.typography.labelSmall)
        }
        OutlinedButton(onClick = {
            viewModel.updateSettings { it.copy(accent = "ember", background = "black", textColor = "auto", fontScale = 1f) }
        }) { Text("Reset to default") }
        LabeledSlider("Text size", settings.fontScale, 0.85f..1.3f, "${(settings.fontScale * 100).toInt()}%") { v ->
            viewModel.updateSettings { it.copy(fontScale = (v * 20).toInt() / 20f) }
        }
    }
}

@Composable
fun DataControlsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    var confirm by remember { mutableStateOf<String?>(null) }
    SubScreen("Data controls", onBack) {
        Hint("Everything lives on this phone unless you use a cloud provider, in which case the messages of that chat are sent to it.")
        listOf(
            "side" to "Delete all side chats",
            "main" to "Clear main chat",
            "feed" to "Clear feed",
            "memory" to "Forget memory",
            "usage" to "Reset token usage",
            "all" to "Erase everything"
        ).forEach { (id, label) ->
            OutlinedButton(onClick = { confirm = id }, modifier = Modifier.fillMaxWidth()) {
                Text(label, color = if (id == "all") Palette.Error else Palette.Text)
            }
        }
        Hint("Erase everything removes chats, feed, goals, routines, Library, media, memory, avatar and settings. Downloaded models stay so you don't have to fetch them again.")
    }
    confirm?.let { id ->
        ConfirmDialog(
            title = "Are you sure?",
            text = "This can't be undone.",
            confirm = "Delete",
            onConfirm = {
                when (id) {
                    "side" -> viewModel.deleteAllSideChats()
                    "main" -> viewModel.clearMainChat()
                    "feed" -> viewModel.clearFeed()
                    "memory" -> viewModel.clearMemory()
                    "usage" -> viewModel.clearUsage()
                    "all" -> viewModel.eraseEverything()
                }
            },
            onDismiss = { confirm = null }
        )
    }
}

@Composable
fun NetworkScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SubScreen("Network", onBack) {
        SwitchRow(
            "Offline mode",
            "Turns off web search and page reading. Cloud providers still work if routing allows them.",
            settings.offlineMode
        ) { c -> viewModel.updateSettings { it.copy(offlineMode = c) } }
        SwitchRow("Download models on Wi-Fi only", "New downloads wait for an unmetered network", settings.wifiOnlyDownloads) { c ->
            viewModel.updateSettings { it.copy(wifiOnlyDownloads = c) }
        }
        SearchSection(viewModel)
    }
}

@Composable
fun LogsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    SubScreen("Logs", onBack, actions = {
        IconButton(onClick = {
            copy(context, logs.joinToString("\n") { "${fmt.format(Date(it.time))} ${it.level} ${it.text}" })
            viewModel.toast("Logs copied")
        }) { Icon(Icons.Filled.ContentCopy, "Copy logs") }
        IconButton(onClick = viewModel::clearLogs) { Icon(Icons.Filled.DeleteSweep, "Clear logs") }
    }) {
        if (logs.isEmpty()) Hint("Nothing logged since the app started.")
        logs.asReversed().forEach { line ->
            Text(
                "${fmt.format(Date(line.time))} ${line.level} ${line.text}",
                style = MaterialTheme.typography.labelSmall,
                color = when (line.level) {
                    "E" -> Palette.Error
                    "W" -> Palette.TerminalAmber
                    else -> Palette.TextMuted
                }
            )
        }
    }
}

@Composable
fun AppLockScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val available = remember {
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }
    SubScreen("App lock", onBack) {
        Hint("Ask for your fingerprint, face or screen lock every time TruAgent opens.")
        SwitchRow("Require unlock", if (available) null else "Set up a screen lock on this phone first", settings.appLock && available) { c ->
            if (available) viewModel.updateSettings { it.copy(appLock = c) } else viewModel.toast("No screen lock or biometrics set up")
        }
    }
}

@Composable
fun DefaultAssistantScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SubScreen("Set as default assistant", onBack) {
        Hint("Make ${settings.assistantName} open when you use your phone's assistant gesture (long-press home or swipe from a bottom corner).")
        Hint("1. Tap the button below.\n2. Choose Digital assistant app (sometimes under Default apps ▸ Assist).\n3. Pick TruAgent.")
        Button(onClick = {
            startSafely(context, Intent(Settings.ACTION_VOICE_INPUT_SETTINGS), Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }, colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black)) { Text("Open assistant settings") }
        OutlinedButton(onClick = { startSafely(context, Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }) { Text("Open default apps") }
        Hint("You can also share text or a picture from any app to TruAgent.")
    }
}

@Composable
fun AboutScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    SubScreen("About TruAgent", onBack) {
        SurfaceCard {
            Text("TruAgent ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
            Hint("A personal AI agent that runs GGUF models on your phone with llama.cpp, or uses a cloud provider you choose. It searches the web, writes your feed, tracks goals, runs routines and keeps a Library.")
        }
        Hint("Built with Kotlin, Jetpack Compose, Room, WorkManager, OkHttp and llama.cpp (MIT).")
        OutlinedButton(onClick = { openUrl(context, "https://github.com/deezi74/Ai-Assistant-Bot") }) { Text("Source code") }
        OutlinedButton(onClick = { openUrl(context, FEEDBACK_URL) }) { Text("Report a problem") }
        OutlinedButton(onClick = { viewModel.push("privacy") }) { Text("Privacy policy") }
    }
}

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    SubScreen("Privacy policy", onBack) {
        listOf(
            "What stays on your phone" to "Chats, feed, goals, routines, Library files, media, memory, skills, your avatar and settings are stored only in TruAgent's private storage on this device. There is no TruAgent account or server.",
            "On-device models" to "When an on-device model answers, your messages never leave the phone.",
            "Cloud providers" to "If you add a provider key and routing uses it, the messages of that conversation (plus the system prompt with your assistant's name, memory, skills and open goals) are sent to that provider under its own privacy terms.",
            "Web search and pages" to "Search queries go to DuckDuckGo or the SearXNG server you set. Pages the assistant reads are fetched directly from their websites.",
            "Model downloads" to "Downloads come straight from Hugging Face. Your Hugging Face token, if any, is sent only to Hugging Face.",
            "Keys and secrets" to "API keys, tokens and environment variables are encrypted with a key held in the Android Keystore and are never part of backups.",
            "Analytics" to "TruAgent has no analytics, ads or tracking.",
            "Deleting data" to "Settings ▸ Data controls removes chats, feed and memory. Uninstalling the app removes everything."
        ).forEach { (title, body) ->
            Text(title, style = MaterialTheme.typography.titleMedium)
            Hint(body)
        }
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("TruAgent", text))
}

@Composable
fun PhoneControlsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val tick = rememberResumeTick()
    val canWrite = remember(tick) { Settings.System.canWrite(context) }
    val dndAccess = remember(tick) {
        context.getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted
    }
    SubScreen("Phone controls", onBack) {
        SwitchRow(
            "Let ${settings.assistantName} control the phone",
            "Uses the phone tool when you ask, for example \"turn on the flashlight\"",
            settings.phoneControl
        ) { c -> viewModel.updateSettings { it.copy(phoneControl = c) } }
        SectionTitle("Works right away")
        Hint("Flashlight on/off, media and ringer volume, alarms and timers, opening any app by name, Wi-Fi/Bluetooth/internet panels, play/pause/skip music, battery level, copying text, opening maps and links. Calls and texts open as drafts and you press call or send.")
        SectionTitle("Needs one-time permission")
        PermissionCard("Screen brightness", "Modify system settings", canWrite) {
            startSafely(context, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
        PermissionCard("Do Not Disturb", "Do Not Disturb access", dndAccess) {
            startSafely(context, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        SectionTitle("Try saying")
        listOf(
            "Turn the flashlight on",
            "Set a timer for 10 minutes",
            "Wake me up at 6:30",
            "Turn the volume down to 30%",
            "Open Spotify",
            "Skip this song",
            "Turn on Do Not Disturb"
        ).forEach { example ->
            Text(
                "“$example”",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.prefill(example) }
                    .padding(vertical = 6.dp)
            )
        }
        Hint("Android does not let normal apps flip Wi-Fi, Bluetooth or airplane mode directly, so for those the assistant opens the quick panel for you. Tapping and typing inside other apps is not part of this build.")
    }
}

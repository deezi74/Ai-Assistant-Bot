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
import dev.pocketagent.agent.TermuxBridge
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
    val googleAccounts by viewModel.googleAccounts.collectAsStateWithLifecycle()
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
            "Google (Gmail, Calendar, Drive)",
            when (googleAccounts.size) {
                0 -> "Not signed in"
                1 -> googleAccounts[0]
                else -> "${googleAccounts.size} accounts"
            },
            connected = googleAccounts.isNotEmpty(), action = if (googleAccounts.isEmpty()) "Sign in" else "Manage"
        ) { viewModel.push("google") }
        val linuxState by viewModel.linuxState.collectAsStateWithLifecycle()
        ConnectorCard(
            "Linux computer",
            when (linuxState) {
                is dev.pocketagent.agent.LinuxEnvironment.State.Ready -> "Installed · ${settings.assistantName} can run Linux commands, Python and Node"
                is dev.pocketagent.agent.LinuxEnvironment.State.Installing -> "Installing…"
                else -> "Not installed · a real Linux inside the app, no Termux needed"
            },
            connected = linuxState is dev.pocketagent.agent.LinuxEnvironment.State.Ready, action = "Set up"
        ) { viewModel.push("linux") }
        ConnectorCard(
            "Voice",
            when (settings.voiceProvider) {
                "openai" -> "Natural voice · OpenAI"
                "openrouter" -> "Natural voice · OpenRouter"
                "elevenlabs" -> "Natural voice · ElevenLabs"
                else -> "Phone voice · pick a natural voice"
            },
            connected = viewModel.cloudVoice.active(), action = "Set up"
        ) { viewModel.push("voice") }
        ConnectorCard(
            "Photos & videos",
            when {
                viewModel.hasGeminiKey && viewModel.hasPollinationsToken -> "AI photos and videos · Gemini and Pollinations keys"
                viewModel.hasGeminiKey -> "AI photos and videos · Gemini key"
                viewModel.hasPollinationsToken -> "AI photos and videos · Pollinations key"
                else -> "Free photos on · add a key for AI videos"
            },
            connected = viewModel.hasGeminiKey || viewModel.hasPollinationsToken, action = "Set up"
        ) { viewModel.push("media") }
        ConnectorCard(
            "Telegram",
            if (settings.telegramEnabled) "On · chat with ${settings.assistantName} from Telegram" else "Off",
            connected = settings.telegramEnabled, action = "Set up"
        ) { viewModel.push("telegram") }
        ConnectorCard(
            "Termux",
            if (settings.termuxEnabled) "On · runs commands and builds apps on the phone" else "Off",
            connected = settings.termuxEnabled, action = "Set up"
        ) { viewModel.push("termux") }
        ConnectorCard(
            "GitHub",
            if (viewModel.hasGithubToken) (if (settings.githubCanWrite) "Connected · read, open issues and comment" else "Connected · read only") else "Not connected",
            connected = viewModel.hasGithubToken, action = if (viewModel.hasGithubToken) "Manage" else "Connect"
        ) { viewModel.push("github") }
        Hint("Contacts are not connected in this build.")
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
fun LinuxScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by viewModel.linuxState.collectAsStateWithLifecycle()
    val output by viewModel.linuxOutput.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var command by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf(false) }
    val ready = state is dev.pocketagent.agent.LinuxEnvironment.State.Ready

    SubScreen("Linux computer", onBack) {
        Hint("A real Linux (Alpine) that lives inside TruAgent. ${settings.assistantName} can install packages, run Python, Node and shell scripts, use git and curl, and keep servers running. No root and no Termux needed. It's sealed off from your phone's files and other apps.")
        when (val s = state) {
            is dev.pocketagent.agent.LinuxEnvironment.State.NotInstalled -> {
                if (!viewModel.linuxBundled) Hint("This phone isn't supported (needs a 64-bit ARM phone).", Palette.Error)
                Button(
                    onClick = { viewModel.installLinux() },
                    enabled = viewModel.linuxBundled,
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black)
                ) { Text("Install Linux (about 300 MB with Python and Node)") }
            }
            is dev.pocketagent.agent.LinuxEnvironment.State.Installing -> Hint(s.step)
            is dev.pocketagent.agent.LinuxEnvironment.State.Failed -> {
                Hint("Install failed: ${s.message}", Palette.Error)
                OutlinedButton(onClick = { viewModel.installLinux() }) { Text("Try again") }
            }
            is dev.pocketagent.agent.LinuxEnvironment.State.Ready -> Hint("Installed and ready.", Palette.Success)
        }
        if (ready) {
            SectionTitle("Try a command")
            androidx.compose.material3.OutlinedTextField(
                value = command, onValueChange = { command = it }, singleLine = true,
                label = { Text("Command, e.g. python3 --version") }, modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.runLinux(command) }, enabled = command.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black)
                ) { Text("Run") }
                OutlinedButton(onClick = { viewModel.runLinux("uname -a; cat /etc/alpine-release; df -h / | tail -1") }) { Text("System info") }
            }
        }
        if (output.isNotBlank()) {
            SurfaceCard(onClick = { copy(context, output) }) {
                Text(output.takeLast(4000), fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (ready) {
            SectionTitle("Try saying")
            listOf(
                "Use your Linux computer to write a Python script that checks Bitcoin's price",
                "Install ffmpeg in your Linux and tell me the version",
                "Start a small web server in Linux and show me it works"
            ).forEach { example -> SurfaceCard(onClick = { viewModel.prefill(example) }) { Text(example) } }
            OutlinedButton(onClick = { removing = true }) { Text("Remove Linux") }
        }
    }
    if (removing) ConfirmDialog("Remove Linux?", "Everything inside it (installed packages and files) is deleted.", "Remove", { viewModel.uninstallLinux() }, { removing = false })
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val options = remember(tick) { viewModel.cloudVoice.options() }
    val hasEleven = remember(tick) { viewModel.hasElevenLabsKey }
    var key by remember { mutableStateOf("") }
    val speaker = remember { dev.pocketagent.ui.Speaker(context, viewModel.cloudVoice) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }

    SubScreen("Voice", onBack) {
        Hint("Pick who reads replies aloud in voice mode and with the speaker button. Natural voices use your own key and need a connection; if one fails it falls back to the phone voice.")
        SectionTitle("Voice service")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o ->
                androidx.compose.material3.FilterChip(
                    selected = settings.voiceProvider == o.id || (o.id == "phone" && options.none { it.id == settings.voiceProvider }),
                    onClick = { viewModel.updateSettings { it.copy(voiceProvider = o.id, voiceName = o.voices.firstOrNull()?.first.orEmpty()) } },
                    label = { Text(o.label) }
                )
            }
        }
        if (options.size == 1) Hint("Add an OpenAI or OpenRouter key in Manage providers, or an ElevenLabs key below, to unlock natural voices.")
        options.firstOrNull { it.id == settings.voiceProvider }?.takeIf { it.voices.isNotEmpty() }?.let { o ->
            SectionTitle("Voice")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                o.voices.forEach { (id, label) ->
                    androidx.compose.material3.FilterChip(
                        selected = settings.voiceName == id,
                        onClick = { viewModel.updateSettings { it.copy(voiceName = id) } },
                        label = { Text(label) }
                    )
                }
            }
        }
        OutlinedButton(onClick = {
            speaker.speak("Hey, it's ${settings.assistantName}. This is how I sound now. Pretty good, right?")
        }) { Text("Test the voice") }

        SectionTitle("ElevenLabs")
        Hint("ElevenLabs has very lifelike voices and a free monthly allowance.")
        OutlinedButton(onClick = { openUrl(context, "https://elevenlabs.io/app/settings/api-keys") }) { Text("Get an ElevenLabs key") }
        SecretField(key, { key = it.trim() }, if (hasEleven) "ElevenLabs key (saved)" else "ElevenLabs API key")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.saveElevenLabsKey(key); key = ""; tick++ },
                enabled = key.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black)
            ) { Text("Save") }
            if (hasEleven) OutlinedButton(onClick = { viewModel.saveElevenLabsKey(null); tick++ }) { Text("Remove") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MediaStudioScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val hasGemini = remember(tick) { viewModel.hasGeminiKey }
    val hasPollinations = remember(tick) { viewModel.hasPollinationsToken }
    var gemini by remember { mutableStateOf("") }
    var pollinations by remember { mutableStateOf("") }

    @Composable
    fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
        androidx.compose.material3.FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
    }

    SubScreen("Photos & videos", onBack) {
        Hint("Ask in chat: \"imagine a neon tiger\", \"make a video of a cat surfing at sunset\", or attach a photo and say \"animate this\" or \"edit this\". Pictures and videos show up in the chat and in Telegram.")

        SectionTitle("AI videos")
        Hint("Videos need one key. A Google AI Studio key is free to create and uses Gemini Omni (it can animate your photos). Video generation may need billing turned on in your Google account.")
        OutlinedButton(onClick = { openUrl(context, "https://aistudio.google.com/apikey") }) { Text("Get a Gemini key") }
        SecretField(gemini, { gemini = it.trim() }, if (hasGemini) "Gemini key (saved)" else "Gemini API key")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.saveGeminiKey(gemini); gemini = ""; tick++ },
                enabled = gemini.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black)
            ) { Text("Save") }
            if (hasGemini) OutlinedButton(onClick = { viewModel.saveGeminiKey(null); tick++ }) { Text("Remove") }
        }
        Hint("Or use a Pollinations key (Veo, Seedance, Wan, Grok video models; uses your Pollinations credits).")
        OutlinedButton(onClick = { openUrl(context, "https://enter.pollinations.ai") }) { Text("Get a Pollinations key") }
        SecretField(pollinations, { pollinations = it.trim() }, if (hasPollinations) "Pollinations key (saved)" else "Pollinations key")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.savePollinationsToken(pollinations); pollinations = ""; tick++ },
                enabled = pollinations.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black)
            ) { Text("Save") }
            if (hasPollinations) OutlinedButton(onClick = { viewModel.savePollinationsToken(null); tick++ }) { Text("Remove") }
        }
        if (hasGemini && hasPollinations) {
            SectionTitle("Video service")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("auto" to "Auto", "gemini" to "Gemini Omni", "pollinations" to "Pollinations").forEach { (id, label) ->
                    Choice(label, settings.videoSource == id) { viewModel.updateSettings { it.copy(videoSource = id) } }
                }
            }
        }
        if (hasPollinations) {
            SectionTitle("Pollinations video model")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "" to "Veo 3.1 fast", "bytedance/seedance-2.0-fast" to "Seedance 2 fast", "alibaba/wan-2.6" to "Wan 2.6",
                    "x-ai/grok-imagine-video" to "Grok Imagine", "google/gemini-omni-1.1-flash" to "Gemini Omni"
                ).forEach { (id, label) ->
                    Choice(label, settings.videoModelPollinations == id) { viewModel.updateSettings { it.copy(videoModelPollinations = id) } }
                }
            }
            SectionTitle("Photo quality")
            Hint("With your Pollinations key, photos use the model you pick here. Without a key they use the free service.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "tongyi-mai/z-image-turbo" to "Z-Image (fast)", "black-forest-labs/flux.2-pro" to "Flux 2 Pro",
                    "bytedance/seedream-4.5" to "Seedream 4.5", "google/gemini-3.1-flash-image" to "Gemini image",
                    "openai/gpt-image-2" to "GPT Image 2", "ideogram-ai/ideogram-v4-quality" to "Ideogram (text in images)"
                ).forEach { (id, label) ->
                    Choice(label, settings.imageModel == id) { viewModel.updateSettings { it.copy(imageModel = id) } }
                }
            }
        }
        SectionTitle("Try saying")
        listOf(
            "Make a video of a golden retriever surfing a huge wave at sunset",
            "Imagine a cyberpunk city street at night, rain, neon reflections",
            "Make a 9:16 video of my logo spinning in chrome"
        ).forEach { example -> SurfaceCard(onClick = { viewModel.prefill(example) }) { Text(example) } }
    }
}

@Composable
fun TelegramConnectorScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val status by viewModel.telegramStatus.collectAsStateWithLifecycle()
    val note by viewModel.telegramNote.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var token by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf(false) }
    val hasToken = remember(note, status) { viewModel.hasTelegramToken }
    val paired = remember(note, status) { viewModel.telegramPaired }

    SubScreen("Telegram", onBack) {
        Hint("Chat with ${settings.assistantName} from Telegram on any device. It uses your own bot and answers only your Telegram account. Replies come from this phone, so it has to be on and online.")
        SectionTitle("Step 1: make a bot")
        Hint("In Telegram, open @BotFather, send /newbot, pick a name, and copy the token it gives you.")
        OutlinedButton(onClick = { openUrl(context, "https://t.me/BotFather") }) { Text("Open BotFather") }
        SecretField(token, { token = it.trim() }, if (hasToken) "Bot token (saved)" else "Bot token")
        Button(
            onClick = { viewModel.saveTelegramToken(token); token = "" },
            enabled = token.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black)
        ) { Text("Connect bot") }
        note?.let { Hint(it, if (it.startsWith("Connected")) Palette.Success else Palette.TextMuted) }

        if (hasToken) {
            SectionTitle("Step 2: pair your account")
            if (paired) {
                Hint("Paired with your Telegram account.", Palette.Success)
                OutlinedButton(onClick = { viewModel.unpairTelegram() }) { Text("Pair a different account") }
            } else {
                Hint("Open your bot in Telegram and send this message:")
                val line = "/start ${viewModel.telegramPairCode}"
                SurfaceCard(onClick = { copy(context, line) }) {
                    Text(line, fontFamily = Mono, style = MaterialTheme.typography.titleMedium)
                    Hint("Tap to copy")
                }
            }
            SectionTitle("Bot")
            SwitchRow("Telegram bot on", "Status: $status", settings.telegramEnabled) { on -> viewModel.setTelegram(on) }
            Hint("While it's on you'll see a notification. Turn off battery optimization for TruAgent so Android doesn't pause it. Telegram chats are saved as a \"Telegram\" chat in the app.")
            OutlinedButton(onClick = { removing = true }) { Text("Remove bot") }
        }
    }
    if (removing) {
        ConfirmDialog("Remove the Telegram bot?", "The token is deleted from this phone and the bot stops answering.", "Remove", { viewModel.removeTelegram() }, { removing = false })
    }
}

@Composable
fun TermuxConnectorScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val status by viewModel.termuxStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val tick = rememberResumeTick()
    val installed = remember(tick) { viewModel.termuxInstalled() }
    var permitted by remember(tick) { mutableStateOf(viewModel.termuxPermitted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permitted = ok
        if (ok) viewModel.updateSettings { it.copy(termuxEnabled = true) }
    }

    SubScreen("Termux", onBack) {
        Hint("Lets ${settings.assistantName} run commands in your Termux: install packages, run scripts and servers like FreeLLMAPI, and build simple Android apps right on the phone. Cloud builds through GitHub are faster and more reliable for big apps.")
        PermissionCard("Termux installed", if (installed) "Found" else "Install Termux from F-Droid", installed) {
            openUrl(context, "https://f-droid.org/packages/com.termux/")
        }
        SectionTitle("Step 1: allow outside apps in Termux")
        Hint("Paste this in Termux once:")
        val line = "mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings"
        SurfaceCard(onClick = { copy(context, line) }) {
            Text(line, fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
            Hint("Tap to copy")
        }
        SectionTitle("Step 2: let TruAgent send commands")
        PermissionCard("Run commands in Termux", "Android permission", permitted) { launcher.launch(TermuxBridge.PERMISSION) }
        SectionTitle("Step 3: turn it on")
        SwitchRow(
            "Let ${settings.assistantName} use Termux",
            "Commands run as you in Termux and can change files there. It explains what a command does before changing things, and refuses commands that would wipe your storage.",
            settings.termuxEnabled && permitted
        ) { on -> if (on && !permitted) launcher.launch(TermuxBridge.PERMISSION) else viewModel.updateSettings { it.copy(termuxEnabled = on) } }
        if (installed && permitted) {
            OutlinedButton(onClick = { viewModel.testTermux() }) { Text("Test the connection") }
            status?.let { Hint(it, if (it.startsWith("Termux is connected")) Palette.Success else Palette.TextMuted) }
        }
        SectionTitle("Try saying")
        listOf(
            "Set up Termux so you can build Android apps",
            "Build me a simple flashlight app on the phone",
            "Start my FreeLLMAPI server",
            "How much storage is Termux using?"
        ).forEach { example -> SurfaceCard(onClick = { viewModel.prefill(example) }) { Text(example) } }
    }
}

@Composable
fun GoogleConnectorScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val accounts by viewModel.googleAccounts.collectAsStateWithLifecycle()
    val status by viewModel.googleStatus.collectAsStateWithLifecycle()
    val consent by viewModel.googleConsent.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }

    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pending?.let { viewModel.googleConsentFinished(it, result.resultCode == android.app.Activity.RESULT_OK) }
        pending = null
    }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)?.let { viewModel.connectGoogle(it) }
    }
    LaunchedEffect(consent) {
        consent?.let { (account, intent) ->
            pending = account
            consentLauncher.launch(intent)
        }
    }

    SubScreen("Google", onBack) {
        Hint("Sign in once and ${settings.assistantName} can use Gmail (read, search, archive, Trash), Google Calendar (what's coming up, and adding events if you allow it) and Google Drive (find and read Docs, Sheets and files). Add as many Google accounts as you like.")
        SectionTitle("Signed in (${accounts.size})")
        if (accounts.isEmpty()) Hint("No Google accounts yet.")
        accounts.forEach { acc ->
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(acc, style = MaterialTheme.typography.titleMedium)
                        Hint("Gmail · Calendar · Drive")
                    }
                    IconButton(onClick = { removing = acc }) { Icon(Icons.Filled.DeleteOutline, "Remove", tint = Palette.TextMuted) }
                }
            }
        }
        Button(
            onClick = {
                chooser.launch(
                    android.accounts.AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null)
                )
            },
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black)
        ) { Text(if (accounts.isEmpty()) "Sign in with Google" else "Add another Google account") }
        status?.let { Hint(it, if (it.startsWith("Connected")) Palette.Success else Palette.TextMuted) }

        SectionTitle("Permissions")
        SwitchRow(
            "Let it organize Gmail",
            "Archive, mark as read and move to Trash when you ask. Nothing is deleted permanently.",
            settings.emailCanOrganize
        ) { c -> viewModel.updateSettings { it.copy(emailCanOrganize = c) } }
        SwitchRow(
            "Let it add calendar events",
            "Only when you ask, after it confirms the details with you.",
            settings.calendarCanWrite
        ) { c -> viewModel.updateSettings { it.copy(calendarCanWrite = c) } }
        Hint("Drive is read only. Sign-in tokens stay with Google Play services on this phone and are never included in backups. Text from mail, events and files is treated as untrusted: instructions hidden in it are ignored. It never sends email.")

        SectionTitle("First time? One-time Google setup")
        Hint("Google only lets registered apps sign in. In Google Cloud console: create a project, enable the Gmail, Calendar and Drive APIs, set the OAuth consent screen to External / Testing with your addresses as test users, then create an Android OAuth client with package ${BuildConfig.APPLICATION_ID.removeSuffix(".debug")} and this app's SHA-1 signing fingerprint.")

        SectionTitle("Try saying")
        listOf("What's on my calendar today?", "Any important emails?", "Find my resume in Drive", "Add lunch with Sam tomorrow at noon").forEach { example ->
            SurfaceCard(onClick = { viewModel.prefill(example) }) { Text(example) }
        }
    }
    removing?.let { acc ->
        ConfirmDialog("Remove $acc?", "${settings.assistantName} stops using its Gmail, Calendar and Drive.", "Remove", { viewModel.disconnectGoogle(acc) }, { removing = null })
    }
}

@Composable
fun GitHubConnectorScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val status by viewModel.githubStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val signIn by viewModel.githubSignIn.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf(viewModel.githubClientId) }
    var removing by remember { mutableStateOf(false) }
    val connected = remember(status) { viewModel.hasGithubToken }

    SubScreen("GitHub", onBack) {
        Hint("${settings.assistantName} can check your repos, issues, pull requests and notifications, read files and search code. Ask things like \"what's new on GitHub?\" or \"summarize the open issues in owner/repo\".")
        SectionTitle(if (connected) "Connected" else "Sign in")
        when (val s = signIn) {
            is AppViewModel.GitHubSignIn.Waiting -> SurfaceCard {
                Text("Your code", style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
                Text(s.userCode, style = MaterialTheme.typography.headlineMedium, fontFamily = Mono)
                Hint("Open GitHub, paste this code and approve. This screen finishes by itself.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { copy(context, s.userCode); openUrl(context, s.url) },
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black)
                    ) { Text("Copy code & open GitHub") }
                    OutlinedButton(onClick = { viewModel.cancelGithubSignIn() }) { Text("Cancel") }
                }
            }
            else -> {
                if (viewModel.githubClientId.isBlank()) {
                    Hint("One-time setup: create an OAuth app on GitHub (Settings ▸ Developer settings ▸ OAuth Apps), tick Enable Device Flow, and paste its Client ID here. It isn't a secret.")
                    OutlinedButton(onClick = { openUrl(context, "https://github.com/settings/applications/new") }) { Text("Create the OAuth app") }
                    AppTextField(clientId, { clientId = it.trim() }, "OAuth app Client ID")
                }
                Button(
                    onClick = { viewModel.startGithubSignIn(clientId.ifBlank { viewModel.githubClientId }) },
                    enabled = signIn !is AppViewModel.GitHubSignIn.Starting,
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black)
                ) { Text(if (connected) "Sign in again with GitHub" else "Sign in with GitHub") }
            }
        }
        status?.let { Hint(it, if (it.startsWith("Connected")) Palette.Success else Palette.TextMuted) }
        if (connected) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.testGithub() }) { Text("Test") }
                OutlinedButton(onClick = { removing = true }) { Text("Disconnect") }
            }
        }

        SectionTitle("Or use a token")
        Hint("Create a fine-grained personal access token. For reading, give it Contents, Issues, Pull requests and Metadata read access on the repos you want. Add Issues write access only if you turn on writing below. Notifications need a classic token with the notifications scope.")
        OutlinedButton(onClick = { openUrl(context, "https://github.com/settings/personal-access-tokens/new") }) { Text("Create a token on GitHub") }
        SecretField(token, { token = it.trim() }, if (connected) "Personal access token (saved)" else "Personal access token")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.saveGithubToken(token); token = "" },
                enabled = token.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Color.Black)
            ) { Text("Save & test") }
        }

        SectionTitle("Build apps for me")
        SwitchRow(
            "Let it build Android apps",
            "Ask for an app and it writes the code, makes a new private repo in your GitHub, and GitHub Actions builds the APK. It only changes repos it created.",
            settings.appBuilder
        ) { c -> viewModel.updateSettings { it.copy(appBuilder = c) } }
        if (viewModel.builtApps.isNotEmpty()) Hint("Apps built so far: " + viewModel.builtApps.joinToString())
        Hint("Building needs repo and workflow access. Sign in again with GitHub if you signed in before 1.8, or give a classic token the repo and workflow scopes. Private repos get 2,000 free build minutes a month, which is roughly 300 builds.")

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

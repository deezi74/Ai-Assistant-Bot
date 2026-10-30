package dev.pocketagent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketagent.core.llm.ProviderPreference
import dev.pocketagent.core.llm.RemoteKind
import dev.pocketagent.core.tools.SearchBackend
import dev.pocketagent.llm.LocalModelState
import dev.pocketagent.ui.theme.Palette
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Palette.Surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProviderSection(viewModel)
            HorizontalDivider(color = Palette.Outline)
            LocalModelSection(viewModel)
            HorizontalDivider(color = Palette.Outline)
            HuggingFaceSection(viewModel)
            HorizontalDivider(color = Palette.Outline)
            RemoteSection(viewModel)
            HorizontalDivider(color = Palette.Outline)
            SearchSection(viewModel)
            HorizontalDivider(color = Palette.Outline)
            PersonaSection(viewModel)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = Palette.Accent)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
}

@Composable
private fun ProviderSection(viewModel: ChatViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SectionTitle("Model routing")
    Hint("Which brain answers. \"First\" options fall back to the other one when the first is unavailable.")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(
            ProviderPreference.LOCAL_FIRST to "Local first",
            ProviderPreference.REMOTE_FIRST to "Remote first"
        ).forEach { (pref, label) ->
            FilterChip(selected = settings.preference == pref, onClick = { viewModel.updateSettings { it.copy(preference = pref) } }, label = { Text(label) })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(
            ProviderPreference.LOCAL_ONLY to "Local only",
            ProviderPreference.REMOTE_ONLY to "Remote only"
        ).forEach { (pref, label) ->
            FilterChip(selected = settings.preference == pref, onClick = { viewModel.updateSettings { it.copy(preference = pref) } }, label = { Text(label) })
        }
    }
    LabeledSlider("Temperature", settings.temperature, 0f..1.5f, String.format(Locale.US, "%.2f", settings.temperature)) { v ->
        viewModel.updateSettings { it.copy(temperature = v) }
    }
    LabeledSlider("Max reply tokens", settings.maxTokens.toFloat(), 128f..4096f, settings.maxTokens.toString()) { v ->
        viewModel.updateSettings { it.copy(maxTokens = v.toInt()) }
    }
    LabeledSlider("Max agent steps", settings.maxSteps.toFloat(), 1f..25f, settings.maxSteps.toString()) { v ->
        viewModel.updateSettings { it.copy(maxSteps = v.toInt()) }
    }
}

@Composable
private fun LocalModelSection(viewModel: ChatViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by viewModel.localState.collectAsStateWithLifecycle()
    val models by viewModel.localModels.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importModel(uri)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { SectionTitle("On-device model (GGUF)") }
        IconButton(onClick = viewModel::refreshLocalModels) { Icon(Icons.Filled.Refresh, "Rescan") }
    }
    when (val s = state) {
        is LocalModelState.Ready -> Hint("Loaded: ${s.fileName}\n${s.description} · ${s.contextSize} ctx · ~${gb(s.estimatedRamBytes)} GB RAM")
        is LocalModelState.Loading -> {
            Hint("Loading ${s.fileName}…")
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        is LocalModelState.Error -> Text(s.message, color = Palette.Error, style = MaterialTheme.typography.bodyMedium)
        LocalModelState.Idle -> Hint("No model loaded. Download one below or import a .gguf file. You can also adb push into Android/data/dev.pocketagent/files/models/.")
    }
    LabeledSlider("Context window", settings.contextSize.toFloat(), 512f..16384f, "${settings.contextSize} tokens") { v ->
        val rounded = (v / 512f).toInt().coerceAtLeast(1) * 512
        viewModel.updateSettings { it.copy(contextSize = rounded) }
    }
    LabeledSlider("CPU threads", settings.threads.toFloat(), 1f..8f, settings.threads.toString()) { v ->
        viewModel.updateSettings { it.copy(threads = v.toInt()) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Load last model on start", Modifier.weight(1f))
        Switch(checked = settings.autoLoadModel, onCheckedChange = { c -> viewModel.updateSettings { it.copy(autoLoadModel = c) } })
    }
    models.forEach { model ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(model.name, style = MaterialTheme.typography.bodyMedium)
                Hint("${gb(model.sizeBytes)} GB")
            }
            TextButton(onClick = { viewModel.loadModel(model) }) { Text("Load") }
            IconButton(onClick = { viewModel.deleteModel(model) }) { Icon(Icons.Filled.Delete, "Delete", tint = Palette.TextMuted) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import .gguf") }
        if (state is LocalModelState.Ready) OutlinedButton(onClick = viewModel::unloadModel) { Text("Unload") }
    }
    importProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
}

@Composable
private fun HuggingFaceSection(viewModel: ChatViewModel) {
    val hf by viewModel.hf.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf("") }

    SectionTitle("Download from Hugging Face")
    Hint("Enter owner/repo or a huggingface.co URL. Q4_K_M files of 0.5B–3B models run well on most phones.")
    OutlinedTextField(
        value = hf.query,
        onValueChange = viewModel::setHfQuery,
        label = { Text("Repository") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = viewModel::listHfFiles, enabled = !hf.loading && hf.query.isNotBlank()) { Text("List files") }
        if (downloads.any { !it.isActive }) OutlinedButton(onClick = viewModel::clearFinishedDownloads) { Text("Clear finished") }
    }
    if (hf.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    hf.error?.let { Text(it, color = Palette.Error, style = MaterialTheme.typography.bodyMedium) }
    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
        hf.files.forEach { file ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(file.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium)
                    Hint("${gb(file.sizeBytes)} GB")
                }
                IconButton(onClick = { viewModel.download(file) }) { Icon(Icons.Filled.Download, "Download") }
            }
        }
    }
    downloads.forEach { d ->
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.fileName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Hint(
                    when {
                        d.isActive && d.totalBytes > 0 -> "${(d.fraction * 100).toInt()}% · ${mb(d.downloadedBytes)}/${mb(d.totalBytes)} MB"
                        d.isActive -> "${mb(d.downloadedBytes)} MB"
                        else -> d.state.name.lowercase()
                    }
                )
                if (d.isActive) IconButton(onClick = { viewModel.cancelDownload(d.id) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, "Cancel")
                }
            }
            if (d.isActive) LinearProgressIndicator(progress = { d.fraction }, modifier = Modifier.fillMaxWidth())
            d.error?.let { Text(it, color = Palette.Error, style = MaterialTheme.typography.bodyMedium) }
        }
    }
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text(if (viewModel.hasHfToken) "HF token (saved, enter to replace)" else "HF token for gated repos (optional)") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
    TextButton(onClick = { viewModel.saveHfToken(token); token = "" }) { Text("Save token") }
}

@Composable
private fun RemoteSection(viewModel: ChatViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var baseUrl by remember(settings.remoteKind) { mutableStateOf(settings.remoteBaseUrl) }
    var model by remember(settings.remoteKind) { mutableStateOf(settings.remoteModel) }
    var key by remember(settings.remoteKind) { mutableStateOf("") }

    SectionTitle("Remote endpoint")
    Hint("Backup brain. Keys are stored with EncryptedSharedPreferences (Android Keystore).")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RemoteKind.entries.forEach { kind ->
            FilterChip(
                selected = settings.remoteKind == kind,
                onClick = { viewModel.selectRemoteKind(kind) },
                label = { Text(kind.label.replace("OpenAI-compatible", "OpenAI API")) }
            )
        }
    }
    OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        value = key,
        onValueChange = { key = it },
        label = { Text(if (viewModel.hasToken(settings.remoteKind)) "API key (saved, enter to replace)" else "API key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Send images (vision models)", Modifier.weight(1f))
        Switch(checked = settings.remoteVision, onCheckedChange = { c -> viewModel.updateSettings { it.copy(remoteVision = c) } })
    }
    Button(onClick = {
        viewModel.updateSettings { it.copy(remoteBaseUrl = baseUrl.trim(), remoteModel = model.trim()) }
        if (key.isNotBlank()) viewModel.saveToken(settings.remoteKind, key)
        key = ""
    }) { Text("Save endpoint") }
}

@Composable
private fun SearchSection(viewModel: ChatViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var searx by remember { mutableStateOf(settings.searxngUrl) }
    SectionTitle("Live web search")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(settings.searchBackend == SearchBackend.DUCKDUCKGO, { viewModel.updateSettings { it.copy(searchBackend = SearchBackend.DUCKDUCKGO) } }, label = { Text("DuckDuckGo") })
        FilterChip(settings.searchBackend == SearchBackend.SEARXNG, { viewModel.updateSettings { it.copy(searchBackend = SearchBackend.SEARXNG) } }, label = { Text("SearXNG") })
    }
    if (settings.searchBackend == SearchBackend.SEARXNG) {
        OutlinedTextField(searx, { searx = it }, label = { Text("SearXNG URL (format=json enabled)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { viewModel.updateSettings { it.copy(searxngUrl = searx.trim()) } }) { Text("Save URL") }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Safe search", Modifier.weight(1f))
        Switch(checked = settings.safeSearch, onCheckedChange = { c -> viewModel.updateSettings { it.copy(safeSearch = c) } })
    }
}

@Composable
private fun PersonaSection(viewModel: ChatViewModel) {
    var soul by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { soul = viewModel.readSoul() }
    SectionTitle("Persona · soul.md")
    Hint("The system prompt that gives the agent its voice. Applied to the next message.")
    OutlinedTextField(
        value = soul,
        onValueChange = { soul = it },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp, max = 360.dp),
        textStyle = MaterialTheme.typography.labelSmall
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { viewModel.saveSoul(soul) }) { Text("Save") }
        OutlinedButton(onClick = { soul = viewModel.resetSoul() }) { Text("Reset to default") }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(display, style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

private fun gb(bytes: Long) = String.format(Locale.US, "%.2f", bytes / 1_073_741_824.0)
private fun mb(bytes: Long) = bytes / 1_048_576

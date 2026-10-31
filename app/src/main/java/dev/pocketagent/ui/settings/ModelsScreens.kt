package dev.pocketagent.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import dev.pocketagent.ui.AppTextField
import dev.pocketagent.ui.AppViewModel
import dev.pocketagent.ui.Hint
import dev.pocketagent.ui.LabeledSlider
import dev.pocketagent.ui.SectionTitle
import dev.pocketagent.ui.SubScreen
import dev.pocketagent.ui.SurfaceCard
import dev.pocketagent.ui.SwitchRow
import dev.pocketagent.ui.formatBytes
import dev.pocketagent.ui.theme.Palette
import java.util.Locale

@Composable
private fun Chip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.AccentSoft, selectedLabelColor = Palette.Accent)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by viewModel.localState.collectAsStateWithLifecycle()
    val models by viewModel.localModels.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importModel(uri)
    }

    SubScreen("Models", onBack, actions = {
        IconButton(onClick = viewModel::refreshLocalModels) { Icon(Icons.Filled.Refresh, "Rescan") }
    }) {
        SectionTitle("Routing")
        Hint("Which brain answers. \"First\" options fall back to the other one when the first is unavailable.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                ProviderPreference.LOCAL_FIRST to "On-device first",
                ProviderPreference.REMOTE_FIRST to "Provider first",
                ProviderPreference.LOCAL_ONLY to "On-device only",
                ProviderPreference.REMOTE_ONLY to "Provider only"
            ).forEach { (pref, label) ->
                Chip(settings.preference == pref, label) { viewModel.updateSettings { it.copy(preference = pref) } }
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

        SectionTitle("On-device model (GGUF)")
        when (val s = state) {
            is LocalModelState.Ready -> Hint("Loaded: ${s.fileName}\n${s.description} · ${s.contextSize} ctx · ~${formatBytes(s.estimatedRamBytes)} RAM")
            is LocalModelState.Loading -> {
                Hint("Loading ${s.fileName}…")
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is LocalModelState.Error -> Hint(s.message, Palette.Error)
            LocalModelState.Idle -> Hint("No model loaded. Download one below or import a .gguf file.")
        }
        LabeledSlider("Context window", settings.contextSize.toFloat(), 512f..16384f, "${settings.contextSize} tokens") { v ->
            val rounded = (v / 512f).toInt().coerceAtLeast(1) * 512
            viewModel.updateSettings { it.copy(contextSize = rounded) }
        }
        LabeledSlider("CPU threads", settings.threads.toFloat(), 1f..8f, settings.threads.toString()) { v ->
            viewModel.updateSettings { it.copy(threads = v.toInt()) }
        }
        SwitchRow("Load last model on start", checked = settings.autoLoadModel) { c -> viewModel.updateSettings { it.copy(autoLoadModel = c) } }
        models.forEach { model ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(model.name, style = MaterialTheme.typography.bodyMedium)
                    Hint(formatBytes(model.sizeBytes))
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

        HuggingFaceSection(viewModel)
    }
}

@Composable
private fun HuggingFaceSection(viewModel: AppViewModel) {
    val hf by viewModel.hf.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf("") }

    SectionTitle("Download from Hugging Face")
    Hint("Enter owner/repo or a huggingface.co URL. Q4_K_M files of 0.5B to 3B models run well on most phones.")
    AppTextField(hf.query, viewModel::setHfQuery, "Repository")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = viewModel::listHfFiles, enabled = !hf.loading && hf.query.isNotBlank()) { Text("List files") }
        if (downloads.any { !it.isActive }) OutlinedButton(onClick = viewModel::clearFinishedDownloads) { Text("Clear finished") }
    }
    if (hf.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    hf.error?.let { Hint(it, Palette.Error) }
    hf.files.forEach { file ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(file.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium)
                Hint(formatBytes(file.sizeBytes))
            }
            IconButton(onClick = { viewModel.download(file) }) { Icon(Icons.Filled.Download, "Download") }
        }
    }
    downloads.forEach { d ->
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.fileName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Hint(
                    when {
                        d.isActive && d.totalBytes > 0 -> "${(d.fraction * 100).toInt()}% · ${formatBytes(d.downloadedBytes)} / ${formatBytes(d.totalBytes)}"
                        d.isActive -> formatBytes(d.downloadedBytes)
                        else -> d.state.name.lowercase()
                    }
                )
                if (d.isActive) IconButton(onClick = { viewModel.cancelDownload(d.id) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, "Cancel")
                }
            }
            if (d.isActive) LinearProgressIndicator(progress = { d.fraction }, modifier = Modifier.fillMaxWidth())
            d.error?.let { Hint(it, Palette.Error) }
        }
    }
    SecretField(
        value = token,
        onChange = { token = it },
        label = if (viewModel.hasHfToken) "HF token (saved, enter to replace)" else "HF token for gated repos (optional)"
    )
    TextButton(onClick = { viewModel.saveHfToken(token); token = "" }) { Text("Save token") }
}

@Composable
fun SecretField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProvidersScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var baseUrl by remember(settings.remoteKind) { mutableStateOf(settings.remoteBaseUrl) }
    var model by remember(settings.remoteKind) { mutableStateOf(settings.remoteModel) }
    var key by remember(settings.remoteKind) { mutableStateOf("") }

    SubScreen("Manage providers", onBack) {
        Hint("A cloud model the assistant can use instead of, or as backup to, the on-device one. Keys are encrypted with the Android Keystore.")
        RemoteKind.entries.forEach { kind ->
            SurfaceCard(onClick = { viewModel.selectRemoteKind(kind) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(kind.label, style = MaterialTheme.typography.titleMedium, color = if (settings.remoteKind == kind) Palette.Accent else Palette.Text)
                        Hint(kind.defaultBaseUrl)
                    }
                    Text(
                        when {
                            settings.remoteKind == kind && viewModel.hasToken(kind) -> "Active"
                            viewModel.hasToken(kind) -> "Key saved"
                            else -> "No key"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (viewModel.hasToken(kind)) Palette.Success else Palette.TextMuted
                    )
                }
            }
        }
        SectionTitle("${settings.remoteKind.label} settings")
        AppTextField(baseUrl, { baseUrl = it }, "Base URL")
        AppTextField(model, { model = it }, "Model")
        SecretField(key, { key = it }, if (viewModel.hasToken(settings.remoteKind)) "API key (saved, enter to replace)" else "API key")
        SwitchRow("Send images (vision models)", checked = settings.remoteVision) { c -> viewModel.updateSettings { it.copy(remoteVision = c) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                viewModel.updateSettings { it.copy(remoteBaseUrl = baseUrl.trim(), remoteModel = model.trim()) }
                if (key.isNotBlank()) viewModel.saveToken(settings.remoteKind, key)
                key = ""
            }) { Text("Save") }
            if (viewModel.hasToken(settings.remoteKind)) {
                OutlinedButton(onClick = { viewModel.saveToken(settings.remoteKind, "") }) { Text("Remove key") }
            }
        }
        SearchSection(viewModel)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchSection(viewModel: AppViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var searx by remember { mutableStateOf(settings.searxngUrl) }
    SectionTitle("Live web search")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip(settings.searchBackend == SearchBackend.DUCKDUCKGO, "DuckDuckGo") { viewModel.updateSettings { it.copy(searchBackend = SearchBackend.DUCKDUCKGO) } }
        Chip(settings.searchBackend == SearchBackend.SEARXNG, "SearXNG") { viewModel.updateSettings { it.copy(searchBackend = SearchBackend.SEARXNG) } }
    }
    if (settings.searchBackend == SearchBackend.SEARXNG) {
        AppTextField(searx, { searx = it }, "SearXNG URL (format=json enabled)")
        TextButton(onClick = { viewModel.updateSettings { it.copy(searxngUrl = searx.trim()) } }) { Text("Save URL") }
    }
    SwitchRow("Safe search", checked = settings.safeSearch) { c -> viewModel.updateSettings { it.copy(safeSearch = c) } }
}

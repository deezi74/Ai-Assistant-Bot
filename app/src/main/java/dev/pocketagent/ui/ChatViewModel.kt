package dev.pocketagent.ui

import android.app.Application
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
import dev.pocketagent.llm.DownloadStatus
import dev.pocketagent.llm.LocalModelFile
import dev.pocketagent.llm.LocalModelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class HfBrowserState(
    val query: String = "",
    val loading: Boolean = false,
    val reference: HfReference? = null,
    val files: List<HfFile> = emptyList(),
    val error: String? = null
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as PocketAgentApp).container

    val chat: StateFlow<ChatUiState> = container.session.state
    val settings: StateFlow<AppSettings> = container.settings.settings
    val localState: StateFlow<LocalModelState> = container.localEngine.state
    val downloads: StateFlow<List<DownloadStatus>> =
        container.downloads.downloads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _localModels = MutableStateFlow<List<LocalModelFile>>(emptyList())
    val localModels: StateFlow<List<LocalModelFile>> = _localModels.asStateFlow()

    private val _hf = MutableStateFlow(HfBrowserState(query = "bartowski/Qwen2.5-1.5B-Instruct-GGUF"))
    val hf: StateFlow<HfBrowserState> = _hf.asStateFlow()

    private val _importProgress = MutableStateFlow<Float?>(null)
    val importProgress: StateFlow<Float?> = _importProgress.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    init {
        refreshLocalModels()
        viewModelScope.launch {
            downloads.collect { list ->
                if (list.any { it.state == androidx.work.WorkInfo.State.SUCCEEDED }) refreshLocalModels()
            }
        }
    }

    fun send(text: String) = container.session.send(text, settings.value.mode)
    fun stop() = container.session.stop()
    fun newChat() = container.session.newChat()
    fun clearTerminal() = container.session.clearTerminal()
    fun consumeToast() { _toast.value = null }

    fun setMode(mode: AgentMode) = container.settings.update { it.copy(mode = mode) }
    fun updateSettings(transform: (AppSettings) -> AppSettings) = container.settings.update(transform)

    fun selectRemoteKind(kind: RemoteKind) = container.settings.update {
        it.copy(remoteKind = kind, remoteBaseUrl = kind.defaultBaseUrl, remoteModel = kind.defaultModel)
    }

    fun hasToken(kind: RemoteKind): Boolean = container.settings.hasToken(kind)
    fun saveToken(kind: RemoteKind, token: String) {
        container.settings.setToken(kind, token)
        _toast.value = if (token.isBlank()) "${kind.label} key removed" else "${kind.label} key saved (encrypted)"
    }

    val hasHfToken: Boolean get() = !container.settings.huggingFaceToken.isNullOrBlank()
    fun saveHfToken(token: String) {
        container.settings.huggingFaceToken = token
        _toast.value = if (token.isBlank()) "Hugging Face token removed" else "Hugging Face token saved"
    }

    fun refreshLocalModels() {
        viewModelScope.launch {
            _localModels.value = withContext(Dispatchers.IO) { container.downloads.localModels() }
        }
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
                _toast.value = "Imported ${file.name}"
                refreshLocalModels()
            } catch (e: Exception) {
                _toast.value = "Import failed: ${e.message}"
            } finally {
                _importProgress.value = null
            }
        }
    }

    fun setHfQuery(q: String) { _hf.value = _hf.value.copy(query = q) }

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
        container.downloads.enqueue(ref, file)
        _toast.value = "Downloading ${file.path.substringAfterLast('/')}"
    }

    fun cancelDownload(id: UUID) = container.downloads.cancel(id)
    fun clearFinishedDownloads() = container.downloads.clearFinished()

    fun readSoul(): String = container.persona.readSoul()
    fun saveSoul(text: String) {
        container.persona.writeSoul(text)
        _toast.value = "soul.md saved"
    }
    fun resetSoul(): String {
        container.persona.resetSoul()
        _toast.value = "soul.md reset to default"
        return container.persona.readSoul()
    }
}

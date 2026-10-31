package dev.pocketagent.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.pocketagent.core.agent.AgentMode
import dev.pocketagent.core.llm.CredentialStore
import dev.pocketagent.core.llm.ProviderPreference
import dev.pocketagent.core.llm.RemoteEndpointConfig
import dev.pocketagent.core.llm.RemoteKind
import dev.pocketagent.core.tools.SearchBackend
import dev.pocketagent.core.tools.SearchSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class AppSettings(
    val mode: AgentMode = AgentMode.WIT,
    val preference: ProviderPreference = ProviderPreference.LOCAL_FIRST,
    val remoteKind: RemoteKind = RemoteKind.OPENROUTER,
    val remoteBaseUrl: String = RemoteKind.OPENROUTER.defaultBaseUrl,
    val remoteModel: String = RemoteKind.OPENROUTER.defaultModel,
    val remoteVision: Boolean = true,
    val searchBackend: SearchBackend = SearchBackend.DUCKDUCKGO,
    val searxngUrl: String = "",
    val safeSearch: Boolean = true,
    val lastModelPath: String? = null,
    val contextSize: Int = 4096,
    val threads: Int = defaultThreads(),
    val temperature: Float = 0.7f,
    val maxTokens: Int = 1024,
    val maxSteps: Int = 12,
    val autoLoadModel: Boolean = true,
    val assistantName: String = "Nova",
    /** "builtin" (value = BuiltinAvatar id) or "photo" (value = file name in files/avatar). */
    val avatarKind: String = "builtin",
    val avatarValue: String = "fox",
    val userName: String = "",
    val feedSteer: String = "Build me a feed about what I care about. Keep it short and direct, easy to skim, no clickbait.",
    val feedIntroDismissed: Boolean = false,
    val accent: String = "ember",
    val amoled: Boolean = true,
    val fontScale: Float = 1.0f,
    val appLock: Boolean = false,
    val wifiOnlyDownloads: Boolean = false,
    val offlineMode: Boolean = false,
    val showTerminal: Boolean = true,
    val keepAwake: Boolean = true
) {
    val remoteConfig: RemoteEndpointConfig
        get() = RemoteEndpointConfig(remoteKind, remoteBaseUrl, remoteModel, remoteVision)

    val searchSettings: SearchSettings
        get() = SearchSettings(searchBackend, searxngUrl, safeSearch = safeSearch)

    companion object {
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
    }
}

/**
 * All persisted settings and secrets, stored in EncryptedSharedPreferences
 * (AES-256 SIV keys, AES-256 GCM values, master key in the Android Keystore).
 */
class SecureSettings(context: Context) : CredentialStore {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = openPrefs()

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    private fun openPrefs(): SharedPreferences = try {
        create()
    } catch (e: Exception) {
        // Keystore entry lost (restore to a new device, OEM bugs): start fresh rather than crash.
        Log.w(TAG, "Encrypted prefs unreadable, resetting", e)
        appContext.deleteSharedPreferences(PREFS_NAME)
        File(appContext.filesDir.parentFile, "shared_prefs/$PREFS_NAME.xml").delete()
        create()
    }

    private fun create(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        val kind = enumOrDefault(prefs.getString("remote_kind", null), d.remoteKind)
        return AppSettings(
            mode = enumOrDefault(prefs.getString("mode", null), d.mode),
            preference = enumOrDefault(prefs.getString("preference", null), d.preference),
            remoteKind = kind,
            remoteBaseUrl = prefs.getString("remote_base_url", null) ?: kind.defaultBaseUrl,
            remoteModel = prefs.getString("remote_model", null) ?: kind.defaultModel,
            remoteVision = prefs.getBoolean("remote_vision", d.remoteVision),
            searchBackend = enumOrDefault(prefs.getString("search_backend", null), d.searchBackend),
            searxngUrl = prefs.getString("searxng_url", null) ?: d.searxngUrl,
            safeSearch = prefs.getBoolean("safe_search", d.safeSearch),
            lastModelPath = prefs.getString("last_model_path", null),
            contextSize = prefs.getInt("context_size", d.contextSize),
            threads = prefs.getInt("threads", d.threads),
            temperature = prefs.getFloat("temperature", d.temperature),
            maxTokens = prefs.getInt("max_tokens", d.maxTokens),
            maxSteps = prefs.getInt("max_steps", d.maxSteps),
            autoLoadModel = prefs.getBoolean("auto_load_model", d.autoLoadModel),
            assistantName = prefs.getString("assistant_name", null) ?: d.assistantName,
            avatarKind = prefs.getString("avatar_kind", null) ?: d.avatarKind,
            avatarValue = prefs.getString("avatar_value", null) ?: d.avatarValue,
            userName = prefs.getString("user_name", null) ?: d.userName,
            feedSteer = prefs.getString("feed_steer", null) ?: d.feedSteer,
            feedIntroDismissed = prefs.getBoolean("feed_intro_dismissed", d.feedIntroDismissed),
            accent = prefs.getString("accent", null) ?: d.accent,
            amoled = prefs.getBoolean("amoled", d.amoled),
            fontScale = prefs.getFloat("font_scale", d.fontScale),
            appLock = prefs.getBoolean("app_lock", d.appLock),
            wifiOnlyDownloads = prefs.getBoolean("wifi_only_downloads", d.wifiOnlyDownloads),
            offlineMode = prefs.getBoolean("offline_mode", d.offlineMode),
            showTerminal = prefs.getBoolean("show_terminal", d.showTerminal),
            keepAwake = prefs.getBoolean("keep_awake", d.keepAwake)
        )
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putString("mode", next.mode.name)
            .putString("preference", next.preference.name)
            .putString("remote_kind", next.remoteKind.name)
            .putString("remote_base_url", next.remoteBaseUrl)
            .putString("remote_model", next.remoteModel)
            .putBoolean("remote_vision", next.remoteVision)
            .putString("search_backend", next.searchBackend.name)
            .putString("searxng_url", next.searxngUrl)
            .putBoolean("safe_search", next.safeSearch)
            .putString("last_model_path", next.lastModelPath)
            .putInt("context_size", next.contextSize)
            .putInt("threads", next.threads)
            .putFloat("temperature", next.temperature)
            .putInt("max_tokens", next.maxTokens)
            .putInt("max_steps", next.maxSteps)
            .putBoolean("auto_load_model", next.autoLoadModel)
            .putString("assistant_name", next.assistantName)
            .putString("avatar_kind", next.avatarKind)
            .putString("avatar_value", next.avatarValue)
            .putString("user_name", next.userName)
            .putString("feed_steer", next.feedSteer)
            .putBoolean("feed_intro_dismissed", next.feedIntroDismissed)
            .putString("accent", next.accent)
            .putBoolean("amoled", next.amoled)
            .putFloat("font_scale", next.fontScale)
            .putBoolean("app_lock", next.appLock)
            .putBoolean("wifi_only_downloads", next.wifiOnlyDownloads)
            .putBoolean("offline_mode", next.offlineMode)
            .putBoolean("show_terminal", next.showTerminal)
            .putBoolean("keep_awake", next.keepAwake)
            .apply()
        _settings.value = next
    }

    override fun getToken(kind: RemoteKind): String? = prefs.getString(tokenKey(kind), null)

    override fun setToken(kind: RemoteKind, token: String?) {
        prefs.edit().apply {
            if (token.isNullOrBlank()) remove(tokenKey(kind)) else putString(tokenKey(kind), token.trim())
        }.apply()
    }

    fun hasToken(kind: RemoteKind): Boolean = !getToken(kind).isNullOrBlank()

    var huggingFaceToken: String?
        get() = prefs.getString("hf_token", null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove("hf_token") else putString("hf_token", value.trim())
            }.apply()
        }

    /** Name → value pairs that tools can reference as $NAME. Stored encrypted. */
    var envVars: Map<String, String>
        get() {
            val raw = prefs.getString("env_vars", null) ?: return emptyMap()
            return try {
                val obj = org.json.JSONObject(raw)
                obj.keys().asSequence().associateWith { obj.optString(it) }
            } catch (e: org.json.JSONException) {
                emptyMap()
            }
        }
        set(value) {
            val obj = org.json.JSONObject()
            value.forEach { (k, v) -> obj.put(k, v) }
            prefs.edit().putString("env_vars", obj.toString()).apply()
        }

    /** Plain (non-secret) settings as JSON, used by Backup. */
    fun exportPublic(): String {
        val obj = org.json.JSONObject()
        prefs.all.forEach { (k, v) ->
            if (!k.startsWith("token_") && k != "hf_token" && k != "env_vars" && v != null) obj.put(k, v)
        }
        return obj.toString(2)
    }

    fun importPublic(json: String) {
        val obj = org.json.JSONObject(json)
        val editor = prefs.edit()
        obj.keys().forEach { k ->
            if (k.startsWith("token_") || k == "hf_token" || k == "env_vars") return@forEach
            when (val v = obj.get(k)) {
                is Boolean -> editor.putBoolean(k, v)
                is Int -> editor.putInt(k, v)
                is Long -> editor.putLong(k, v)
                is Double -> editor.putFloat(k, v.toFloat())
                is Float -> editor.putFloat(k, v)
                is String -> editor.putString(k, v)
                else -> Unit
            }
        }
        editor.commit()
        _settings.value = load()
    }

    fun wipe() {
        prefs.edit().clear().commit()
        _settings.value = load()
    }

    private fun tokenKey(kind: RemoteKind) = "token_${kind.name.lowercase()}"

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    companion object {
        private const val TAG = "SecureSettings"
        private const val PREFS_NAME = "pocketagent_secure"
    }
}

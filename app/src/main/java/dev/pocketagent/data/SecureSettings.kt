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
    /** Ids from Backgrounds / TextColors in ui.theme. */
    val background: String = "black",
    val textColor: String = "auto",
    /** Lets the assistant use the phone tool (flashlight, volume, alarms, opening apps). */
    val phoneControl: Boolean = true,
    /** Reflect after tasks and save lessons, facts and skills. */
    val autoLearn: Boolean = true,
    /** Skip long hidden reasoning in Wit mode (Qwen3 /no_think) so replies start sooner. */
    val quickReplies: Boolean = true,
    /** Lets the assistant see the phone's location for briefs, distances and nearby places. */
    val shareLocation: Boolean = false,
    /** Lets the email tool move messages to Trash or archive them. */
    val emailCanOrganize: Boolean = true,
    /** Lets the GitHub tool open issues and post comments (reading is always allowed). */
    val githubCanWrite: Boolean = false,
    /** Lets the calendar tool add events to Google Calendar. */
    val calendarCanWrite: Boolean = false,
    /** What the AI-generated character looks like; shared with the model so it can stay in character. */
    val characterDescription: String = "",
    /** "free", "openai" or "openrouter": where AI characters are drawn. */
    val imageSource: String = "free",
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
            background = prefs.getString("background", null) ?: if (prefs.getBoolean("amoled", true)) "black" else "charcoal",
            textColor = prefs.getString("text_color", null) ?: d.textColor,
            phoneControl = prefs.getBoolean("phone_control", d.phoneControl),
            autoLearn = prefs.getBoolean("auto_learn", d.autoLearn),
            quickReplies = prefs.getBoolean("quick_replies", d.quickReplies),
            shareLocation = prefs.getBoolean("share_location", d.shareLocation),
            emailCanOrganize = prefs.getBoolean("email_can_organize", d.emailCanOrganize),
            githubCanWrite = prefs.getBoolean("github_can_write", d.githubCanWrite),
            calendarCanWrite = prefs.getBoolean("calendar_can_write", d.calendarCanWrite),
            characterDescription = prefs.getString("character_description", null) ?: d.characterDescription,
            imageSource = prefs.getString("image_source", null) ?: d.imageSource,
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
            .putString("background", next.background)
            .putString("text_color", next.textColor)
            .putBoolean("phone_control", next.phoneControl)
            .putBoolean("auto_learn", next.autoLearn)
            .putBoolean("quick_replies", next.quickReplies)
            .putBoolean("share_location", next.shareLocation)
            .putBoolean("email_can_organize", next.emailCanOrganize)
            .putBoolean("github_can_write", next.githubCanWrite)
            .putBoolean("calendar_can_write", next.calendarCanWrite)
            .putString("character_description", next.characterDescription)
            .putString("image_source", next.imageSource)
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

    // Connector secrets. The "token_" prefix keeps them out of Backup exports.

    var pollinationsToken: String?
        get() = secret("token_pollinations")
        set(value) = putSecret("token_pollinations", value)

    var googlePlacesKey: String?
        get() = secret("token_google_places")
        set(value) = putSecret("token_google_places", value)

    /** GitHub personal access token for the github tool. */
    var githubToken: String?
        get() = secret("token_github")
        set(value) = putSecret("token_github", value)

    /** OAuth app Client ID for "Sign in with GitHub" (public, not a secret). */
    var githubClientId: String
        get() = prefs.getString("github_client_id", null)?.takeIf { it.isNotBlank() } ?: dev.pocketagent.BuildConfig.GITHUB_CLIENT_ID
        set(value) = prefs.edit().putString("github_client_id", value.trim()).apply()

    /** Google accounts signed in with "Sign in with Google" (addresses only; tokens stay with Play services). */
    var googleAccounts: List<String>
        get() = prefs.getString("google_accounts", null).orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        set(value) = prefs.edit().putString("google_accounts", value.distinct().joinToString("\n")).apply()

    /** IMAP accounts for the email tool, stored as one encrypted JSON value. */
    var emailAccounts: List<EmailAccount>
        get() {
            val raw = secret("token_email_accounts")
            if (raw == null) {
                // 1.5.x stored a single account in separate keys.
                val host = secret("token_email_host") ?: return emptyList()
                val user = secret("token_email_user") ?: return emptyList()
                val pass = secret("token_email_password") ?: return emptyList()
                return listOf(EmailAccount(host, secret("token_email_port")?.toIntOrNull() ?: 993, user, pass))
            }
            return try {
                val arr = org.json.JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    EmailAccount(o.getString("host"), o.optInt("port", 993), o.getString("user"), o.getString("password"))
                }
            } catch (e: org.json.JSONException) {
                emptyList()
            }
        }
        set(value) {
            val arr = org.json.JSONArray()
            value.forEach { a ->
                arr.put(org.json.JSONObject().put("host", a.host).put("port", a.port).put("user", a.user).put("password", a.password))
            }
            putSecret("token_email_accounts", if (value.isEmpty()) null else arr.toString())
            listOf("token_email_host", "token_email_port", "token_email_user", "token_email_password").forEach { putSecret(it, null) }
        }

    private fun secret(key: String): String? = prefs.getString(key, null)?.takeIf { it.isNotBlank() }

    private fun putSecret(key: String, value: String?) {
        prefs.edit().apply { if (value.isNullOrBlank()) remove(key) else putString(key, value.trim()) }.apply()
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

/** [oauth] accounts come from Sign in with Google and use XOAUTH2 instead of a password. */
data class EmailAccount(val host: String, val port: Int, val user: String, val password: String, val oauth: Boolean = false)

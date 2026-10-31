package dev.pocketagent.agent

import dev.pocketagent.core.llm.RemoteKind
import dev.pocketagent.data.SecureSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Natural-sounding voices for spoken replies, with the user's own key:
 * OpenAI (gpt-4o-mini-tts), OpenRouter (the same model routed) or ElevenLabs.
 * Returns MP3 files; the caller falls back to the phone's voice on any error.
 */
class CloudVoice(private val settings: SecureSettings, private val cacheDir: File) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    data class Option(val id: String, val label: String, val voices: List<Pair<String, String>>)

    fun options(): List<Option> = buildList {
        add(Option(PHONE, "Phone voice", emptyList()))
        if (settings.hasToken(RemoteKind.OPENAI_COMPATIBLE)) add(Option(OPENAI, "OpenAI", OPENAI_VOICES))
        if (settings.hasToken(RemoteKind.OPENROUTER)) add(Option(OPENROUTER, "OpenRouter", OPENAI_VOICES))
        if (!settings.elevenLabsKey.isNullOrBlank()) add(Option(ELEVENLABS, "ElevenLabs", ELEVEN_VOICES))
    }

    /** True when a cloud voice is picked and its key is present. */
    fun active(): Boolean {
        val p = settings.current.voiceProvider
        return p != PHONE && options().any { it.id == p }
    }

    /** Speaks [text] into an MP3 file. */
    suspend fun synthesize(text: String): File = withContext(Dispatchers.IO) {
        val s = settings.current
        val dir = File(cacheDir, "voice").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 10 * 60_000 }?.forEach { it.delete() }
        val out = File(dir, "say_${System.nanoTime()}.mp3")
        val request = when (s.voiceProvider) {
            ELEVENLABS -> {
                val key = settings.elevenLabsKey ?: throw IOException("No ElevenLabs key")
                val voice = s.voiceName.takeIf { it.isNotBlank() && OPENAI_VOICES.none { v -> v.first == it } } ?: ELEVEN_VOICES.first().first
                Request.Builder()
                    .url("https://api.elevenlabs.io/v1/text-to-speech/$voice?output_format=mp3_44100_128")
                    .header("xi-api-key", key)
                    .header("Accept", "audio/mpeg")
                    .post(JSONObject().put("text", text).put("model_id", "eleven_flash_v2_5").toString().toRequestBody(JSON))
                    .build()
            }
            OPENROUTER -> {
                val key = settings.getToken(RemoteKind.OPENROUTER) ?: throw IOException("No OpenRouter key")
                Request.Builder()
                    .url("https://openrouter.ai/api/v1/audio/speech")
                    .header("Authorization", "Bearer $key")
                    .header("X-Title", "TruAgent")
                    .post(openAiBody("openai/gpt-4o-mini-tts-2025-12-15", text, s.voiceName).toString().toRequestBody(JSON))
                    .build()
            }
            else -> {
                val key = settings.getToken(RemoteKind.OPENAI_COMPATIBLE) ?: throw IOException("No OpenAI key")
                Request.Builder()
                    .url(RemoteKind.OPENAI_COMPATIBLE.defaultBaseUrl.trimEnd('/') + "/audio/speech")
                    .header("Authorization", "Bearer $key")
                    .post(openAiBody("gpt-4o-mini-tts", text, s.voiceName).toString().toRequestBody(JSON))
                    .build()
            }
        }
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = resp.body?.string().orEmpty()
                val msg = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") ?: JSONObject(body).optJSONObject("detail")?.optString("message") }.getOrNull()
                throw IOException(msg?.takeIf { it.isNotBlank() } ?: "Voice service answered HTTP ${resp.code}")
            }
            val type = resp.header("Content-Type").orEmpty()
            if (type.startsWith("application/json")) throw IOException("Voice service sent no audio")
            resp.body?.byteStream()?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: throw IOException("Empty audio")
        }
        if (out.length() < 200) throw IOException("Empty audio")
        out
    }

    private fun openAiBody(model: String, text: String, voice: String) = JSONObject()
        .put("model", model)
        .put("input", text)
        .put("voice", voice.takeIf { v -> OPENAI_VOICES.any { it.first == v } } ?: "coral")
        .put("response_format", "mp3")
        .put("instructions", "Speak naturally and warmly, like a quick-witted friend. Natural pace, expressive, not robotic.")

    companion object {
        const val PHONE = "phone"
        const val OPENAI = "openai"
        const val OPENROUTER = "openrouter"
        const val ELEVENLABS = "elevenlabs"
        private val JSON = "application/json".toMediaType()

        val OPENAI_VOICES = listOf(
            "coral" to "Coral", "nova" to "Nova", "shimmer" to "Shimmer", "sage" to "Sage", "ballad" to "Ballad",
            "alloy" to "Alloy", "ash" to "Ash", "echo" to "Echo", "onyx" to "Onyx", "verse" to "Verse", "fable" to "Fable"
        )

        /** ElevenLabs premade voices (IDs from their public voice library). */
        val ELEVEN_VOICES = listOf(
            "21m00Tcm4TlvDq8ikWAM" to "Rachel", "EXAVITQu4vr4xnSDxMaL" to "Sarah", "JBFqnCBsd6RMkjVDRZzb" to "George",
            "pNInz6obpgDQGcFmaJgB" to "Adam", "TX3LPaxmHKxFdv7VOQHJ" to "Liam", "XrExE9yKIg1WjnnlVkGX" to "Matilda"
        )
    }
}

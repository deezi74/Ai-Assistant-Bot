package dev.pocketagent.agent

import android.util.Base64
import dev.pocketagent.core.llm.RemoteKind
import dev.pocketagent.data.SecureSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class CharacterStyle(val id: String, val label: String, val prompt: String)

data class ImageSource(val id: String, val label: String, val note: String)

/**
 * Draws a custom character for the assistant from a description. The free source
 * needs no account; the others use the user's own OpenAI or OpenRouter key.
 */
class CharacterGenerator(private val settings: SecureSettings) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    fun sources(): List<ImageSource> = buildList {
        add(ImageSource(FREE, "Free", "Pollinations, no account needed"))
        if (settings.hasToken(RemoteKind.OPENAI_COMPATIBLE)) add(ImageSource(OPENAI, "OpenAI", "gpt-image-1 with your key"))
        if (settings.hasToken(RemoteKind.OPENROUTER)) add(ImageSource(OPENROUTER, "OpenRouter", "Gemini image model with your key"))
    }

    fun buildPrompt(description: String, style: CharacterStyle): String =
        "Original fictional character, portrait avatar of ${description.trim().trimEnd('.')}. ${style.prompt}. " +
            "Head and shoulders, centered, facing the viewer, friendly expressive face, clean simple background, " +
            "square composition, no text, no watermark, no logos."

    /** Returns image bytes (JPEG or PNG). */
    suspend fun generate(description: String, style: CharacterStyle, seed: Int, sourceId: String): ByteArray = withContext(Dispatchers.IO) {
        require(description.isNotBlank()) { "Describe the character first" }
        val prompt = buildPrompt(description, style)
        when (sourceId) {
            OPENAI -> openAi(prompt)
            OPENROUTER -> openRouter(prompt)
            else -> free(prompt, seed)
        }
    }

    /**
     * Any picture from a free-form prompt, or an edit of [input] (JPEG/PNG bytes).
     * Editing needs an OpenAI or OpenRouter key; the free service only creates.
     */
    suspend fun image(prompt: String, width: Int, height: Int, seed: Int, sourceId: String, input: ByteArray? = null): ByteArray =
        withContext(Dispatchers.IO) {
            require(prompt.isNotBlank()) { "Describe the picture first" }
            val full = prompt.trim() + ". No watermark."
            if (input != null) {
                when {
                    sourceId == OPENROUTER || (sourceId != OPENAI && settings.hasToken(RemoteKind.OPENROUTER)) -> openRouter(full, input)
                    settings.hasToken(RemoteKind.OPENAI_COMPATIBLE) -> openAiEdit(full, input)
                    else -> throw IOException("Editing a photo needs an OpenAI or OpenRouter key (Settings ▸ Manage providers). The free image service can only make new pictures.")
                }
            } else {
                when (sourceId) {
                    OPENAI -> openAi(full, size = if (width > height) "1536x1024" else if (height > width) "1024x1536" else "1024x1024")
                    OPENROUTER -> openRouter(full)
                    else -> free(full, seed, width, height)
                }
            }
        }

    private fun openAiEdit(prompt: String, input: ByteArray): ByteArray {
        val token = settings.getToken(RemoteKind.OPENAI_COMPATIBLE) ?: throw IOException("Add an OpenAI key in Manage providers")
        val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("model", "gpt-image-1")
            .addFormDataPart("prompt", prompt)
            .addFormDataPart("image", "input.jpg", input.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val req = Request.Builder()
            .url(RemoteKind.OPENAI_COMPATIBLE.defaultBaseUrl.trimEnd('/') + "/images/edits")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException(errorMessage(text) ?: "OpenAI answered HTTP ${resp.code}")
            val item = JSONObject(text).optJSONArray("data")?.optJSONObject(0) ?: throw IOException("OpenAI returned no image")
            item.optString("b64_json").takeIf { it.isNotEmpty() }?.let { return Base64.decode(it, Base64.DEFAULT) }
            return download(item.optString("url").takeIf { it.isNotEmpty() } ?: throw IOException("OpenAI returned no image"))
        }
    }

    private fun free(prompt: String, seed: Int, width: Int = 768, height: Int = 768): ByteArray {
        val token = settings.pollinationsToken
        if (!token.isNullOrBlank()) {
            try {
                return keyed(prompt, seed, width, height, token)
            } catch (e: IOException) {
                // Fall back to the anonymous service below.
            }
        }
        val url = "https://image.pollinations.ai/prompt/".toHttpUrl().newBuilder()
            .addPathSegment(prompt)
            .addQueryParameter("width", width.toString())
            .addQueryParameter("height", height.toString())
            .addQueryParameter("seed", seed.toString())
            .addQueryParameter("nologo", "true")
            .addQueryParameter("safe", "true")
            .build()
        val request = Request.Builder().url(url).header("Accept", "image/*").apply {
            settings.pollinationsToken?.let { header("Authorization", "Bearer $it") }
        }.build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Image service answered HTTP ${resp.code}. Try again in a minute.")
            val type = resp.header("Content-Type").orEmpty()
            val bytes = resp.body?.bytes() ?: throw IOException("Empty image")
            if (!type.startsWith("image/")) throw IOException("Image service is busy (${type.ifEmpty { "no image" }}). Try again.")
            return bytes
        }
    }

    /** Pollinations with the user's key: better models (Flux 2, GPT Image, Seedream…) and no queue. */
    private fun keyed(prompt: String, seed: Int, width: Int, height: Int, token: String): ByteArray {
        val model = settings.current.imageModel.ifBlank { "tongyi-mai/z-image-turbo" }
        val url = "https://gen.pollinations.ai/image/".toHttpUrl().newBuilder()
            .addPathSegment(prompt)
            .addQueryParameter("model", model)
            .addQueryParameter("width", width.toString())
            .addQueryParameter("height", height.toString())
            .addQueryParameter("seed", seed.toString())
            .build()
        val request = Request.Builder().url(url).header("Accept", "image/*").header("Authorization", "Bearer $token").build()
        http.newCall(request).execute().use { resp ->
            val type = resp.header("Content-Type").orEmpty()
            if (!resp.isSuccessful || !type.startsWith("image/")) {
                throw IOException(errorMessage(resp.body?.string().orEmpty()) ?: "Pollinations answered HTTP ${resp.code}")
            }
            return resp.body?.bytes() ?: throw IOException("Empty image")
        }
    }

    private fun openAi(prompt: String, size: String = "1024x1024"): ByteArray {
        val token = settings.getToken(RemoteKind.OPENAI_COMPATIBLE) ?: throw IOException("Add an OpenAI key in Manage providers")
        val s = settings.current
        val base = if (s.remoteKind == RemoteKind.OPENAI_COMPATIBLE && s.remoteBaseUrl.contains("openai.com")) s.remoteBaseUrl else RemoteKind.OPENAI_COMPATIBLE.defaultBaseUrl
        val body = JSONObject()
            .put("model", "gpt-image-1")
            .put("prompt", prompt)
            .put("size", size)
            .put("n", 1)
        val req = Request.Builder()
            .url(base.trimEnd('/') + "/images/generations")
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException(errorMessage(text) ?: "OpenAI answered HTTP ${resp.code}")
            val item = JSONObject(text).optJSONArray("data")?.optJSONObject(0) ?: throw IOException("OpenAI returned no image")
            item.optString("b64_json").takeIf { it.isNotEmpty() }?.let { return Base64.decode(it, Base64.DEFAULT) }
            val url = item.optString("url").takeIf { it.isNotEmpty() } ?: throw IOException("OpenAI returned no image")
            return download(url)
        }
    }

    private fun openRouter(prompt: String, input: ByteArray? = null): ByteArray {
        val token = settings.getToken(RemoteKind.OPENROUTER) ?: throw IOException("Add an OpenRouter key in Manage providers")
        val body = JSONObject()
            .put("model", OPENROUTER_IMAGE_MODEL)
            .put("modalities", JSONArray().put("image").put("text"))
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "content",
                        if (input == null) prompt else JSONArray()
                            .put(JSONObject().put("type", "text").put("text", prompt))
                            .put(
                                JSONObject().put("type", "image_url").put(
                                    "image_url",
                                    JSONObject().put("url", "data:image/jpeg;base64," + Base64.encodeToString(input, Base64.NO_WRAP))
                                )
                            )
                    )
                )
            )
        val req = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $token")
            .header("X-Title", "TruAgent")
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException(errorMessage(text) ?: "OpenRouter answered HTTP ${resp.code}")
            val message = JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw IOException("OpenRouter returned no image")
            val url = message.optJSONArray("images")?.optJSONObject(0)?.optJSONObject("image_url")?.optString("url")
                ?: throw IOException("This OpenRouter model returned text instead of an image")
            return if (url.startsWith("data:")) Base64.decode(url.substringAfter(","), Base64.DEFAULT) else download(url)
        }
    }

    private fun download(url: String): ByteArray =
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Could not download the image (HTTP ${resp.code})")
            resp.body?.bytes() ?: throw IOException("Empty image")
        }

    private fun errorMessage(body: String): String? = try {
        JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    } catch (e: org.json.JSONException) {
        null
    }

    companion object {
        const val FREE = "free"
        const val OPENAI = "openai"
        const val OPENROUTER = "openrouter"
        private const val OPENROUTER_IMAGE_MODEL = "google/gemini-2.5-flash-image"
        private val JSON = "application/json".toMediaType()

        val styles = listOf(
            CharacterStyle("3d", "3D cartoon", "Pixar-style 3D animated character render, soft studio lighting, vibrant colors"),
            CharacterStyle("anime", "Anime", "anime illustration, clean line art, cel shading, bright colors"),
            CharacterStyle("pixel", "Pixel art", "16-bit pixel art sprite portrait, crisp pixels, limited palette"),
            CharacterStyle("chibi", "Chibi", "cute chibi sticker style, big head, big eyes, thick outline"),
            CharacterStyle("clay", "Clay", "claymation figure, handmade plasticine texture, soft shadows"),
            CharacterStyle("watercolor", "Watercolor", "loose watercolor painting, paper texture, soft edges"),
            CharacterStyle("comic", "Comic", "bold comic book illustration, ink outlines, halftone shading"),
            CharacterStyle("realistic", "Realistic", "photorealistic digital portrait, cinematic lighting, shallow depth of field")
        )

        fun style(id: String?): CharacterStyle = styles.firstOrNull { it.id == id } ?: styles.first()
    }
}

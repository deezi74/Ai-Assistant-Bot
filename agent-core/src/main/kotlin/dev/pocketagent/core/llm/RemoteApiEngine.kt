package dev.pocketagent.core.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class RemoteKind(val label: String, val defaultBaseUrl: String, val defaultModel: String) {
    OPENAI_COMPATIBLE("OpenAI-compatible", "https://api.openai.com/v1", "gpt-4o-mini"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "openrouter/auto"),
    ANTHROPIC("Anthropic", "https://api.anthropic.com/v1", "claude-sonnet-5-5"),
    /** Self-hosted router over many free-tier providers (github.com/tashfeenahmed/freellmapi), OpenAI-compatible. */
    FREELLMAPI("FreeLLMAPI (free models)", "http://localhost:3001/v1", "auto")
}

data class RemoteEndpointConfig(
    val kind: RemoteKind = RemoteKind.OPENROUTER,
    val baseUrl: String = kind.defaultBaseUrl,
    val model: String = kind.defaultModel,
    val visionEnabled: Boolean = true
)

/** Secret storage for API tokens. The Android app backs this with EncryptedSharedPreferences. */
interface CredentialStore {
    fun getToken(kind: RemoteKind): String?
    fun setToken(kind: RemoteKind, token: String?)
}

/**
 * Streams chat completions from OpenAI-compatible servers (OpenAI, OpenRouter,
 * Groq, LM Studio, llama-server, vLLM, Ollama's /v1) or Anthropic's Messages API.
 * Tokens travel as `Authorization: Bearer` (OpenAI/OpenRouter) or `x-api-key`
 * (Anthropic, which does not accept bearer auth for API keys).
 */
class RemoteApiEngine(
    private val credentials: CredentialStore,
    initialConfig: RemoteEndpointConfig = RemoteEndpointConfig(),
    httpClient: OkHttpClient? = null,
    private val appReferer: String = "https://github.com/pocketagent",
    private val appTitle: String = "TruAgent"
) : LLMProvider {

    @Volatile
    var config: RemoteEndpointConfig = initialConfig

    private val client: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override val id: String get() = "remote:${config.kind.name.lowercase()}"
    override val displayName: String get() = "${config.kind.label} · ${config.model}"
    override val supportsVision: Boolean get() = config.visionEnabled

    override suspend fun isReady(): Boolean {
        val token = credentials.getToken(config.kind)
        val local = isLocalUrl(config.baseUrl)
        return config.model.isNotBlank() && config.baseUrl.isNotBlank() && (local || !token.isNullOrBlank())
    }

    override suspend fun release() {
        client.dispatcher.cancelAll()
    }

    override fun stream(messages: List<ChatMessage>, params: GenerationParams): Flow<LlmEvent> = callbackFlow {
        val cfg = config
        val token = credentials.getToken(cfg.kind)
        val request = try {
            when (cfg.kind) {
                RemoteKind.ANTHROPIC -> buildAnthropicRequest(cfg, token, messages, params)
                RemoteKind.OPENAI_COMPATIBLE, RemoteKind.OPENROUTER, RemoteKind.FREELLMAPI -> buildOpenAiRequest(cfg, token, messages, params)
            }
        } catch (e: IllegalArgumentException) {
            trySend(LlmEvent.Failure("Invalid endpoint URL: ${cfg.baseUrl}", e))
            trySend(LlmEvent.Done(FinishReason.ERROR, 0))
            close()
            return@callbackFlow
        }

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) {
                    trySend(LlmEvent.Done(FinishReason.CANCELLED, 0))
                } else {
                    trySend(LlmEvent.Failure("Network error: ${e.message}", e))
                    trySend(LlmEvent.Done(FinishReason.ERROR, 0))
                }
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty().take(2000)
                        trySend(LlmEvent.Failure("HTTP ${resp.code} from ${cfg.kind.label}: ${extractErrorMessage(body)}"))
                        trySend(LlmEvent.Done(FinishReason.ERROR, 0))
                        close()
                        return
                    }
                    val source = resp.body?.source()
                    if (source == null) {
                        trySend(LlmEvent.Failure("Empty response body"))
                        trySend(LlmEvent.Done(FinishReason.ERROR, 0))
                        close()
                        return
                    }
                    val parser = SseStreamParser(cfg.kind)
                    try {
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            for (event in parser.onLine(line)) {
                                trySend(event)
                            }
                            if (parser.finished) break
                        }
                        trySend(LlmEvent.Done(parser.finishReason, parser.generatedChunks))
                    } catch (e: IOException) {
                        if (call.isCanceled()) {
                            trySend(LlmEvent.Done(FinishReason.CANCELLED, parser.generatedChunks))
                        } else {
                            trySend(LlmEvent.Failure("Stream interrupted: ${e.message}", e))
                            trySend(LlmEvent.Done(FinishReason.ERROR, parser.generatedChunks))
                        }
                    }
                    close()
                }
            }
        })
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    /** Lists model ids from `/models` (OpenAI-compatible and OpenRouter). */
    fun listModels(): List<String> {
        val cfg = config
        val token = credentials.getToken(cfg.kind)
        val builder = Request.Builder().url(cfg.baseUrl.trimEnd('/') + "/models").get()
        when (cfg.kind) {
            RemoteKind.ANTHROPIC -> {
                if (!token.isNullOrBlank()) builder.header("x-api-key", token)
                builder.header("anthropic-version", ANTHROPIC_VERSION)
            }
            else -> if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw LlmException("HTTP ${resp.code} listing models")
            val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            val data = root["data"] as? JsonArray ?: return emptyList()
            return data.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }.sorted()
        }
    }

    internal fun buildOpenAiRequest(
        cfg: RemoteEndpointConfig,
        token: String?,
        messages: List<ChatMessage>,
        params: GenerationParams
    ): Request {
        // Plain http is only for a router on this phone or the home network (FreeLLMAPI, Ollama, LM Studio).
        require(!cfg.baseUrl.trim().startsWith("http://", ignoreCase = true) || isPrivateHost(cfg.baseUrl)) {
            "http:// is only allowed for localhost or LAN addresses"
        }
        val body = buildJsonObject {
            put("model", cfg.model)
            put("stream", true)
            put("max_tokens", params.maxTokens)
            put("temperature", params.temperature)
            put("top_p", params.topP)
            if (params.seed >= 0) put("seed", params.seed)
            if (cfg.kind == RemoteKind.OPENROUTER || cfg.baseUrl.contains("api.openai.com")) {
                putJsonObject("stream_options") { put("include_usage", true) }
            }
            putJsonArray("messages") {
                for (m in messages) {
                    addJsonObject {
                        put("role", m.role.wireName)
                        if (m.imageJpegBase64 != null && cfg.visionEnabled && m.role == ChatRole.USER) {
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", m.content)
                                }
                                addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        put("url", "data:image/jpeg;base64,${m.imageJpegBase64}")
                                    }
                                }
                            }
                        } else {
                            put("content", m.content)
                        }
                    }
                }
            }
        }
        val builder = Request.Builder()
            .url(cfg.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(JSON_MEDIA))
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        if (cfg.kind == RemoteKind.OPENROUTER) {
            builder.header("HTTP-Referer", appReferer)
            builder.header("X-Title", appTitle)
        }
        return builder.build()
    }

    internal fun buildAnthropicRequest(
        cfg: RemoteEndpointConfig,
        token: String?,
        messages: List<ChatMessage>,
        params: GenerationParams
    ): Request {
        val system = messages.filter { it.role == ChatRole.SYSTEM }.joinToString("\n\n") { it.content }
        val turns = mergeConsecutive(messages.filter { it.role != ChatRole.SYSTEM })
        val body = buildJsonObject {
            put("model", cfg.model)
            put("stream", true)
            put("max_tokens", params.maxTokens)
            put("temperature", params.temperature.coerceIn(0f, 1f))
            if (system.isNotBlank()) put("system", system)
            putJsonArray("messages") {
                for (m in turns) {
                    addJsonObject {
                        put("role", m.role.wireName)
                        putJsonArray("content") {
                            if (m.imageJpegBase64 != null && cfg.visionEnabled && m.role == ChatRole.USER) {
                                addJsonObject {
                                    put("type", "image")
                                    putJsonObject("source") {
                                        put("type", "base64")
                                        put("media_type", "image/jpeg")
                                        put("data", m.imageJpegBase64)
                                    }
                                }
                            }
                            addJsonObject {
                                put("type", "text")
                                put("text", m.content.ifEmpty { " " })
                            }
                        }
                    }
                }
            }
        }
        val builder = Request.Builder()
            .url(cfg.baseUrl.trimEnd('/') + "/messages")
            .header("Accept", "text/event-stream")
            .header("anthropic-version", ANTHROPIC_VERSION)
            .post(body.toString().toRequestBody(JSON_MEDIA))
        if (!token.isNullOrBlank()) builder.header("x-api-key", token)
        return builder.build()
    }

    /** Anthropic requires strictly alternating user/assistant turns that start with user. */
    private fun mergeConsecutive(messages: List<ChatMessage>): List<ChatMessage> {
        val out = ArrayList<ChatMessage>()
        for (m in messages) {
            val last = out.lastOrNull()
            if (last != null && last.role == m.role) {
                out[out.size - 1] = last.copy(
                    content = last.content + "\n\n" + m.content,
                    imageJpegBase64 = m.imageJpegBase64 ?: last.imageJpegBase64
                )
            } else {
                out.add(m)
            }
        }
        if (out.firstOrNull()?.role == ChatRole.ASSISTANT) {
            out.add(0, ChatMessage(ChatRole.USER, "(conversation resumed)"))
        }
        return out
    }

    private fun extractErrorMessage(body: String): String = try {
        val root = json.parseToJsonElement(body).jsonObject
        val error = root["error"]
        when (error) {
            is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull ?: body
            is JsonPrimitive -> error.contentOrNull ?: body
            else -> root["message"]?.jsonPrimitive?.contentOrNull ?: body
        }
    } catch (e: Exception) {
        body.ifBlank { "(no body)" }
    }

    private fun isLocalUrl(url: String): Boolean = isPrivateHost(url)

    /** Incremental parser for the two SSE dialects. */
    class SseStreamParser(private val kind: RemoteKind) {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        var finished = false
            private set
        var finishReason = FinishReason.STOP
            private set
        var generatedChunks = 0
            private set
        private var anthropicInputTokens = 0

        fun onLine(rawLine: String): List<LlmEvent> {
            val line = rawLine.trimEnd('\r')
            if (!line.startsWith("data:")) return emptyList()
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) return emptyList()
            if (payload == "[DONE]") {
                finished = true
                return emptyList()
            }
            val obj = try {
                json.parseToJsonElement(payload).jsonObject
            } catch (e: Exception) {
                return emptyList()
            }
            return when (kind) {
                RemoteKind.ANTHROPIC -> parseAnthropic(obj)
                RemoteKind.OPENAI_COMPATIBLE, RemoteKind.OPENROUTER, RemoteKind.FREELLMAPI -> parseOpenAi(obj)
            }
        }

        private fun parseOpenAi(obj: JsonObject): List<LlmEvent> {
            val events = ArrayList<LlmEvent>(2)
            (obj["error"] as? JsonObject)?.let { err ->
                events += LlmEvent.Failure(err["message"]?.jsonPrimitive?.contentOrNull ?: err.toString())
                finished = true
                finishReason = FinishReason.ERROR
                return events
            }
            (obj["usage"] as? JsonObject)?.let { usage ->
                val prompt = (usage["prompt_tokens"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                val completion = (usage["completion_tokens"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                if (prompt != null || completion != null) events += LlmEvent.Usage(prompt ?: 0, completion ?: 0)
            }
            val choices = obj["choices"] as? JsonArray ?: return events
            val choice = choices.firstOrNull() as? JsonObject ?: return events
            val delta = choice["delta"] as? JsonObject
            if (delta != null) {
                val reasoning = (delta["reasoning_content"] ?: delta["reasoning"])
                    ?.let { (it as? JsonPrimitive)?.contentOrNull }
                if (!reasoning.isNullOrEmpty()) events += LlmEvent.Reasoning(reasoning)
                val content = (delta["content"] as? JsonPrimitive)?.contentOrNull
                if (!content.isNullOrEmpty()) {
                    generatedChunks++
                    events += LlmEvent.Token(content)
                }
            }
            val reason = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
            if (reason != null) {
                finishReason = if (reason == "length") FinishReason.LENGTH else FinishReason.STOP
            }
            return events
        }

        private fun parseAnthropic(obj: JsonObject): List<LlmEvent> {
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
            return when (type) {
                "content_block_delta" -> {
                    val delta = obj["delta"] as? JsonObject ?: return emptyList()
                    when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                        "text_delta" -> {
                            val text = delta["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (text.isEmpty()) emptyList() else {
                                generatedChunks++
                                listOf(LlmEvent.Token(text))
                            }
                        }
                        "thinking_delta" -> {
                            val text = delta["thinking"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (text.isEmpty()) emptyList() else listOf(LlmEvent.Reasoning(text))
                        }
                        else -> emptyList()
                    }
                }
                "message_start" -> {
                    val usage = (obj["message"] as? JsonObject)?.get("usage") as? JsonObject
                    val input = usage?.get("input_tokens")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    if (input != null) {
                        anthropicInputTokens = input
                        listOf(LlmEvent.PromptProcessed(input))
                    } else {
                        emptyList()
                    }
                }
                "message_delta" -> {
                    val reason = ((obj["delta"] as? JsonObject)?.get("stop_reason") as? JsonPrimitive)?.contentOrNull
                    if (reason == "max_tokens") finishReason = FinishReason.LENGTH
                    val output = ((obj["usage"] as? JsonObject)?.get("output_tokens") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                    if (output != null) listOf(LlmEvent.Usage(anthropicInputTokens, output)) else emptyList()
                }
                "message_stop" -> {
                    finished = true
                    emptyList()
                }
                "error" -> {
                    finished = true
                    finishReason = FinishReason.ERROR
                    val msg = ((obj["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
                    listOf(LlmEvent.Failure(msg ?: obj.toString()))
                }
                else -> emptyList()
            }
        }
    }

    companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val PRIVATE_HOST = Regex(
            """^https?://(localhost|127\.\d+\.\d+\.\d+|10\.\d+\.\d+\.\d+|192\.168\.\d+\.\d+|172\.(1[6-9]|2\d|3[01])\.\d+\.\d+|[a-z0-9-]+\.local)(:\d+)?(/.*)?$""",
            RegexOption.IGNORE_CASE
        )

        /** Localhost, LAN addresses and .local names: the only places plain http:// is allowed. */
        fun isPrivateHost(url: String): Boolean = PRIVATE_HOST.matches(url.trim())
    }
}

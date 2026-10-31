package dev.pocketagent.agent

import android.util.Base64
import dev.pocketagent.AppContainer
import dev.pocketagent.core.agent.AgentEvent
import dev.pocketagent.core.llm.ChatMessage
import dev.pocketagent.core.llm.ChatRole
import dev.pocketagent.data.ConversationEntity
import dev.pocketagent.data.MessageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Lets the user talk to the assistant from Telegram through their own bot
 * (token from @BotFather). It long-polls Telegram while the bot service runs,
 * answers only the one chat that paired with the code shown in the app, and
 * keeps the conversation as a "Telegram" chat inside TruAgent.
 */
class TelegramBot(private val container: AppContainer) {
    private val settings get() = container.settings
    private val dao get() = container.database.conversations()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .build()

    private val _status = MutableStateFlow("Off")
    val status: StateFlow<String> = _status.asStateFlow()
    private var job: Job? = null

    /** Code the user sends as "/start CODE" to pair their Telegram chat. */
    val pairingCode: String
        get() = settings.telegramPairCode ?: (100000 + Random.nextInt(900000)).toString().also { settings.telegramPairCode = it }

    val running: Boolean get() = job?.isActive == true

    fun start(scope: CoroutineScope) {
        if (running) return
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        _status.value = "Off"
    }

    /** Checks the token; returns the bot's @username. */
    suspend fun test(token: String): String = withContext(Dispatchers.IO) {
        val o = call(token, "getMe", JSONObject())
        "@" + o.getJSONObject("result").optString("username")
    }

    private suspend fun loop() {
        var offset = settings.telegramOffset
        var backoff = 2_000L
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val token = settings.telegramToken
            if (token.isNullOrBlank()) {
                _status.value = "No bot token"
                return
            }
            try {
                _status.value = if (settings.telegramChatId == null) "Waiting for you to pair (send /start $pairingCode to your bot)" else "Running"
                val res = call(token, "getUpdates", JSONObject().put("timeout", 50).put("offset", offset), longPoll = true)
                val updates = res.optJSONArray("result") ?: continue
                for (i in 0 until updates.length()) {
                    val u = updates.getJSONObject(i)
                    offset = u.getLong("update_id") + 1
                    settings.telegramOffset = offset
                    u.optJSONObject("message")?.let { handle(token, it) }
                }
                backoff = 2_000L
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = "Connection problem, retrying: ${e.message}"
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    private suspend fun handle(token: String, msg: JSONObject) {
        val chatId = msg.getJSONObject("chat").getLong("id")
        val text = (msg.optString("text").ifBlank { msg.optString("caption") }).trim()
        val paired = settings.telegramChatId
        if (paired == null) {
            if (text == "/start $pairingCode" || text == pairingCode) {
                settings.telegramChatId = chatId
                settings.telegramPairCode = null
                send(token, chatId, "Paired. I'm ${settings.current.assistantName}. Talk to me here like in the app.")
            } else {
                send(token, chatId, "This is a private assistant. To pair, send the code shown in TruAgent ▸ Settings ▸ Connectors ▸ Telegram.")
            }
            return
        }
        if (chatId != paired) {
            send(token, chatId, "This is a private assistant.")
            return
        }
        if (text == "/start") {
            send(token, chatId, "I'm here. Ask me anything.")
            return
        }
        val image = msg.optJSONArray("photo")?.let { photos -> runCatching { photo(token, photos.getJSONObject(photos.length() - 1).getString("file_id")) }.getOrNull() }
        val prompt = text.ifBlank { if (image != null) "What's in this image?" else return }
        reply(token, chatId, prompt, image)
    }

    private suspend fun reply(token: String, chatId: Long, prompt: String, image: String?) {
        val conversationId = conversation()
        val history = dao.messages(conversationId).filter { it.role != "tool" }.takeLast(20).map {
            ChatMessage(if (it.role == "user") ChatRole.USER else ChatRole.ASSISTANT, it.content)
        }
        dao.insertMessage(MessageEntity(conversationId = conversationId, role = "user", content = prompt, createdAt = System.currentTimeMillis()))
        val typing = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                runCatching { call(token, "sendChatAction", JSONObject().put("chat_id", chatId).put("action", "typing")) }
                delay(4_500)
            }
        }
        var answer = ""
        val images = ArrayList<File>()
        try {
            val mode = settings.current.mode
            container.runner.newLoop()
                .run(history, prompt, mode, container.runner.extraContext(mode, prompt) + "\n- The user is writing from Telegram; keep replies short and plain.", image)
                .collect { e ->
                    when (e) {
                        is AgentEvent.Observation -> MakeVideoTool.mediaFrom(e.output)?.let { File(container.workspace.mediaDir, it) }?.takeIf { it.isFile }?.let { images += it }
                        is AgentEvent.Completed -> answer = e.answer
                        is AgentEvent.Failed -> answer = "Something went wrong: ${e.message}"
                        else -> Unit
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            answer = "Something went wrong: ${e.message}"
        } finally {
            typing.cancel()
        }
        images.forEach { runCatching { if (it.name.endsWith(".mp4")) sendVideo(token, chatId, it) else sendPhoto(token, chatId, it) } }
        if (answer.isNotBlank()) {
            send(token, chatId, answer)
            dao.insertMessage(MessageEntity(conversationId = conversationId, role = "assistant", content = answer, createdAt = System.currentTimeMillis()))
        }
        dao.touch(conversationId, System.currentTimeMillis())
    }

    private suspend fun conversation(): Long {
        settings.telegramConversationId?.let { id -> if (dao.conversation(id) != null) return id }
        val now = System.currentTimeMillis()
        val id = dao.insertConversation(ConversationEntity(title = "Telegram", mode = settings.current.mode.name, createdAt = now, updatedAt = now))
        settings.telegramConversationId = id
        return id
    }

    private fun photo(token: String, fileId: String): String {
        val path = call(token, "getFile", JSONObject().put("file_id", fileId)).getJSONObject("result").getString("file_path")
        val bytes = http.newCall(Request.Builder().url("https://api.telegram.org/file/bot$token/$path").build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("photo download failed")
            r.body!!.bytes()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun send(token: String, chatId: Long, text: String) {
        text.chunked(4000).forEach { part -> call(token, "sendMessage", JSONObject().put("chat_id", chatId).put("text", part)) }
    }

    private fun sendPhoto(token: String, chatId: Long, file: File) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId.toString())
            .addFormDataPart("photo", file.name, file.asRequestBody("image/jpeg".toMediaType()))
            .build()
        http.newCall(Request.Builder().url("https://api.telegram.org/bot$token/sendPhoto").post(body).build()).execute().close()
    }

    private fun sendVideo(token: String, chatId: Long, file: File) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId.toString())
            .addFormDataPart("supports_streaming", "true")
            .addFormDataPart("video", file.name, file.asRequestBody("video/mp4".toMediaType()))
            .build()
        val client = http.newBuilder().writeTimeout(3, TimeUnit.MINUTES).build()
        client.newCall(Request.Builder().url("https://api.telegram.org/bot$token/sendVideo").post(body).build()).execute().close()
    }

    private fun call(token: String, method: String, body: JSONObject, longPoll: Boolean = false): JSONObject {
        val req = Request.Builder()
            .url("https://api.telegram.org/bot$token/$method")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val client = if (longPoll) http else http
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val o = runCatching { JSONObject(text) }.getOrNull() ?: throw IllegalStateException("Telegram error ${r.code}")
            if (!o.optBoolean("ok")) {
                throw IllegalStateException(
                    if (r.code == 401) "Telegram rejected the bot token" else o.optString("description").ifBlank { "Telegram error ${r.code}" }
                )
            }
            return o
        }
    }
}

package dev.pocketagent.core

import dev.pocketagent.core.llm.ChatMessage
import dev.pocketagent.core.llm.ChatRole
import dev.pocketagent.core.llm.CredentialStore
import dev.pocketagent.core.llm.HfReference
import dev.pocketagent.core.llm.HuggingFaceClient
import dev.pocketagent.core.llm.LlmEvent
import dev.pocketagent.core.llm.RemoteApiEngine
import dev.pocketagent.core.llm.RemoteEndpointConfig
import dev.pocketagent.core.llm.RemoteKind
import dev.pocketagent.core.tools.WebSearchTool
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

private class MemCreds : CredentialStore {
    val map = HashMap<RemoteKind, String>()
    override fun getToken(kind: RemoteKind) = map[kind]
    override fun setToken(kind: RemoteKind, token: String?) {
        if (token == null) map.remove(kind) else map[kind] = token
    }
}

class RemoteAndSearchTest {

    @Test
    fun openAiStreamIsParsedWithBearerAuth() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"reasoning\":\"hmm\",\"content\":\"lo\"},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n"
            )
        )
        server.start()
        val creds = MemCreds().apply { setToken(RemoteKind.OPENAI_COMPATIBLE, "sk-test") }
        val engine = RemoteApiEngine(creds, RemoteEndpointConfig(RemoteKind.OPENAI_COMPATIBLE, server.url("/v1").toString(), "m"))
        val events = engine.stream(listOf(ChatMessage(ChatRole.USER, "hi"))).toList()
        assertEquals("Hello", events.filterIsInstance<LlmEvent.Token>().joinToString("") { it.text })
        assertEquals("hmm", events.filterIsInstance<LlmEvent.Reasoning>().single().text)
        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
        assertTrue(req.body.readUtf8().contains("\"stream\":true"))
        server.shutdown()
    }

    @Test
    fun anthropicStreamUsesApiKeyHeaderAndSystemField() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":12}}}\n\n" +
                    "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Yo\"}}\n\n" +
                    "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
            )
        )
        server.start()
        val creds = MemCreds().apply { setToken(RemoteKind.ANTHROPIC, "ak") }
        val engine = RemoteApiEngine(creds, RemoteEndpointConfig(RemoteKind.ANTHROPIC, server.url("/v1").toString(), "claude-sonnet-5-5"))
        val events = engine.stream(
            listOf(ChatMessage(ChatRole.SYSTEM, "be brief"), ChatMessage(ChatRole.USER, "hi"))
        ).toList()
        assertEquals("Yo", events.filterIsInstance<LlmEvent.Token>().single().text)
        val req = server.takeRequest()
        assertEquals("ak", req.getHeader("x-api-key"))
        assertEquals(RemoteApiEngine.ANTHROPIC_VERSION, req.getHeader("anthropic-version"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"system\":\"be brief\""))
        server.shutdown()
    }

    @Test
    fun httpErrorBecomesFailure() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"bad key\"}}"))
        server.start()
        val creds = MemCreds().apply { setToken(RemoteKind.OPENROUTER, "x") }
        val engine = RemoteApiEngine(creds, RemoteEndpointConfig(RemoteKind.OPENROUTER, server.url("/api/v1").toString(), "m"))
        val failure = engine.stream(listOf(ChatMessage(ChatRole.USER, "hi"))).toList().filterIsInstance<LlmEvent.Failure>().single()
        assertTrue(failure.message.contains("bad key"))
        server.shutdown()
    }

    @Test
    fun duckDuckGoHtmlIsParsed() {
        val html = """
            <div class="result results_links web-result"><div class="links_main">
              <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2F&amp;rut=abc">Kotlin Programming Language</a>
              <a class="result__snippet">Concise. Cross-platform. Fun.</a>
            </div></div>
            <div class="result result--ad"><a class="result__a" href="https://ads.example">Ad</a></div>
        """.trimIndent()
        val hits = WebSearchTool.parseDuckDuckGoHtml(html, 5)
        assertEquals(1, hits.size)
        assertEquals("https://kotlinlang.org/", hits[0].url)
        assertEquals("Concise. Cross-platform. Fun.", hits[0].snippet)
    }

    @Test
    fun huggingFaceReferencesParse() {
        assertEquals(HfReference("a/b", null), HfReference.parse("a/b"))
        assertEquals(HfReference("a/b", "x.gguf"), HfReference.parse("a/b:x.gguf"))
        assertEquals(HfReference("a/b", "dir/x.gguf", "main"), HfReference.parse("https://huggingface.co/a/b/resolve/main/dir/x.gguf?download=true"))
    }

    @Test
    fun resumableDownloadCompletesPartFile() {
        val server = MockWebServer()
        val payload = ByteArray(300_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setResponseCode(206).setBody(okio.Buffer().write(payload, 100_000, 200_000)))
        server.start()
        val dir = Files.createTempDirectory("dl").toFile()
        val dest = java.io.File(dir, "m.gguf")
        java.io.File(dir, "m.gguf.part").writeBytes(payload.copyOfRange(0, 100_000))
        var last = 0L
        HuggingFaceClient().download(server.url("/f").toString(), dest, null) { done, _ -> last = done; true }
        assertEquals("bytes=100000-", server.takeRequest().getHeader("Range"))
        assertEquals(300_000L, last)
        assertTrue(dest.readBytes().contentEquals(payload))
        server.shutdown()
    }
}

package dev.pocketagent.core

import dev.pocketagent.core.tools.GitHubDeviceFlow
import dev.pocketagent.core.tools.GoogleApi
import dev.pocketagent.core.tools.GoogleCalendarTool
import dev.pocketagent.core.tools.GoogleDriveTool
import dev.pocketagent.core.tools.GoogleTokens
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class GoogleAndDeviceFlowTest {
    private class FakeTokens : GoogleTokens {
        var current = "stale"
        val invalidated = mutableListOf<String>()
        override val accounts = listOf("me@gmail.com")
        override suspend fun token(account: String) = current
        override fun invalidate(account: String, token: String) { invalidated += token; current = "fresh" }
    }

    @Test
    fun calendarRetriesWithFreshTokenAndListsEvents() = runTest {
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != "Bearer fresh") return MockResponse().setResponseCode(401)
                return when {
                    request.path!!.startsWith("/calendars/primary/events") && request.method == "GET" -> MockResponse().setBody(
                        """{"items":[{"summary":"Dentist","location":"Main St","start":{"dateTime":"2026-11-02T15:00:00Z"},"end":{"dateTime":"2026-11-02T16:00:00Z"}}]}"""
                    )
                    request.path!!.startsWith("/calendars/primary/events") -> MockResponse().setBody("""{"htmlLink":"https://cal/x"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        s.start()
        val tokens = FakeTokens()
        val api = GoogleApi(tokens)
        val base = s.url("/").toString().trimEnd('/')
        val utc = { ZoneId.of("UTC") }
        val r = GoogleCalendarTool(api, { false }, utc, base).execute(buildJsonObject { put("action", JsonPrimitive("upcoming")) })
        val blocked = GoogleCalendarTool(api, { false }, utc, base).execute(buildJsonObject {
            put("action", JsonPrimitive("add")); put("title", JsonPrimitive("Lunch")); put("start", JsonPrimitive("2026-11-03T12:00"))
        })
        val added = GoogleCalendarTool(api, { true }, utc, base).execute(buildJsonObject {
            put("action", JsonPrimitive("add")); put("title", JsonPrimitive("Lunch")); put("start", JsonPrimitive("2026-11-03T12:00"))
        })
        val body = s.takeRequest().let { s.takeRequest() }.let { s.takeRequest() }.body.readUtf8()
        s.shutdown()
        assertEquals(listOf("stale"), tokens.invalidated)
        assertFalse(r.output, r.isError)
        assertTrue(r.output, r.output.contains("Mon Nov 2 3:00 PM · Dentist · Main St (until 4:00 PM)"))
        assertTrue(blocked.isError)
        assertFalse(added.output, added.isError)
        assertTrue(body, body.contains("2026-11-03T13:00:00Z"))
    }

    @Test
    fun driveExportsGoogleDocsAsText() = runTest {
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path!!
                return when {
                    p.startsWith("/files/abcdefghijkl/export") -> MockResponse().setBody("Meeting notes body")
                    p.startsWith("/files/abcdefghijkl") -> MockResponse().setBody("""{"id":"abcdefghijkl","name":"Notes","mimeType":"application/vnd.google-apps.document"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        s.start()
        val tokens = FakeTokens().apply { current = "fresh" }
        val tool = GoogleDriveTool(GoogleApi(tokens), s.url("/").toString().trimEnd('/'))
        val r = tool.execute(buildJsonObject { put("action", JsonPrimitive("read")); put("id", JsonPrimitive("abcdefghijkl")) })
        s.shutdown()
        assertTrue(r.output, r.output.contains("Notes (Google Doc):\nMeeting notes body"))
    }

    @Test
    fun deviceFlowReportsPendingThenToken() = runTest {
        val s = MockWebServer()
        s.enqueue(MockResponse().setBody("""{"device_code":"dc","user_code":"ABCD-1234","verification_uri":"https://github.com/login/device","interval":5,"expires_in":900}"""))
        s.enqueue(MockResponse().setBody("""{"error":"authorization_pending"}"""))
        s.enqueue(MockResponse().setBody("""{"access_token":"gho_x","token_type":"bearer"}"""))
        s.start()
        val flow = GitHubDeviceFlow(s.url("/").toString())
        val code = flow.start("cid")
        assertEquals("ABCD-1234", code.userCode)
        assertEquals(GitHubDeviceFlow.Poll.Pending, flow.poll("cid", code.deviceCode))
        assertEquals(GitHubDeviceFlow.Poll.Token("gho_x"), flow.poll("cid", code.deviceCode))
        s.shutdown()
    }
}

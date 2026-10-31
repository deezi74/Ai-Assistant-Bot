package dev.pocketagent.core

import dev.pocketagent.core.tools.GitHubClient
import dev.pocketagent.core.tools.GitHubTool
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
import java.util.Base64

class GitHubToolTest {
    private fun server(): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != "Bearer secret") return MockResponse().setResponseCode(401)
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/repos/me/app/issues?") -> MockResponse().setBody(
                        """[{"number":4,"title":"Crash on start","state":"open","user":{"login":"sam"},"comments":2,"updated_at":"2026-10-30T10:00:00Z"},
                           {"number":5,"title":"A PR","state":"open","user":{"login":"sam"},"pull_request":{}}]"""
                    )
                    path.startsWith("/repos/me/app/contents/src/Main.kt") -> MockResponse().setBody(
                        """{"type":"file","size":11,"content":"${Base64.getEncoder().encodeToString("fun main()\n".toByteArray())}"}"""
                    )
                    path == "/repos/me/app/issues" && request.method == "POST" ->
                        MockResponse().setBody("""{"number":9,"html_url":"https://github.com/me/app/issues/9"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        start()
    }

    @Test
    fun listsIssuesWithoutPullRequestsAndReadsFiles() = runTest {
        val s = server()
        val tool = GitHubTool(GitHubClient({ "secret" }, base = s.url("/").toString()), canWrite = { false })
        val issues = tool.execute(buildJsonObject { put("action", JsonPrimitive("issues")); put("repo", JsonPrimitive("me/app")) })
        val file = tool.execute(buildJsonObject {
            put("action", JsonPrimitive("file")); put("repo", JsonPrimitive("me/app")); put("path", JsonPrimitive("/src/../src/Main.kt"))
        })
        s.shutdown()
        assertFalse(issues.output, issues.isError)
        assertTrue(issues.output, issues.output.contains("#4 Crash on start"))
        assertFalse(issues.output, issues.output.contains("A PR"))
        assertTrue(file.output, file.output.contains("fun main()"))
    }

    @Test
    fun writesNeedTheSwitchAndBadTokensExplainThemselves() = runTest {
        val s = server()
        val base = s.url("/").toString()
        val create = buildJsonObject {
            put("action", JsonPrimitive("create_issue")); put("repo", JsonPrimitive("me/app")); put("title", JsonPrimitive("Bug"))
        }
        val blocked = GitHubTool(GitHubClient({ "secret" }, base = base), canWrite = { false }).execute(create)
        val allowed = GitHubTool(GitHubClient({ "secret" }, base = base), canWrite = { true }).execute(create)
        val badToken = GitHubTool(GitHubClient({ "wrong" }, base = base), canWrite = { true }).execute(create)
        s.shutdown()
        assertTrue(blocked.isError)
        assertTrue(blocked.output, blocked.output.contains("turned off"))
        assertFalse(allowed.output, allowed.isError)
        assertTrue(allowed.output, allowed.output.contains("issues/9"))
        assertTrue(badToken.output, badToken.output.contains("401"))
        assertEquals(false, GitHubTool(GitHubClient({ null }), { true }).isAvailable())
    }
}

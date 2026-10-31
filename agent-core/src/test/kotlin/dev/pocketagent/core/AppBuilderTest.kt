package dev.pocketagent.core

import dev.pocketagent.core.tools.AndroidAppTemplate
import dev.pocketagent.core.tools.AppBuilderTool
import dev.pocketagent.core.tools.GitHubClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBuilderTest {
    @Test
    fun templateHasAWorkingSkeleton() {
        val files = AndroidAppTemplate.files("Tip \"Calc\"", "com.dom.tipcalc")
        assertTrue(files.containsKey("app/src/main/java/com/dom/tipcalc/MainActivity.kt"))
        assertTrue(files[".github/workflows/build.yml"]!!.contains("gradle --no-daemon :app:assembleRelease"))
        assertTrue(files["app/build.gradle.kts"]!!.contains("applicationId = \"com.dom.tipcalc\""))
        assertTrue(files["app/src/main/java/com/dom/tipcalc/MainActivity.kt"]!!.contains("Tapped \$taps times"))
        assertTrue(files[".github/workflows/build.yml"]!!.contains("\${{ github.run_number }}"))
    }

    @Test
    fun errorLinesArePulledFromLogs() {
        val log = "2026-10-31T10:00:00.000Z > Task :app:compileReleaseKotlin\n" +
            "2026-10-31T10:00:01.000Z e: file:///app/MainActivity.kt:12:5 Unresolved reference 'Foo'.\n" +
            "2026-10-31T10:00:02.000Z FAILURE: Build failed with an exception.\n"
        val out = AppBuilderTool.errorsFrom(log)
        assertTrue(out, out.contains("e: file:///app/MainActivity.kt:12:5 Unresolved reference 'Foo'."))
        assertFalse(out, out.contains("2026-10-31T"))
    }

    @Test
    fun createsRepoCommitsTemplateAndRefusesOtherRepos() = runTest {
        val s = MockWebServer()
        val paths = mutableListOf<String>()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path!!
                synchronized(paths) { paths += "${request.method} $p" }
                return when {
                    p == "/user/repos" -> MockResponse().setBody("""{"full_name":"dom/tip-calc"}""")
                    p == "/repos/dom/tip-calc" -> MockResponse().setBody("""{"default_branch":"main"}""")
                    p == "/repos/dom/tip-calc/git/ref/heads/main" -> MockResponse().setBody("""{"object":{"sha":"h1"}}""")
                    p == "/repos/dom/tip-calc/git/commits/h1" -> MockResponse().setBody("""{"tree":{"sha":"t1"}}""")
                    p == "/repos/dom/tip-calc/git/trees" -> MockResponse().setBody("""{"sha":"t2"}""")
                    p == "/repos/dom/tip-calc/git/commits" -> MockResponse().setBody("""{"sha":"c2abcdef"}""")
                    p == "/repos/dom/tip-calc/git/refs/heads/main" -> MockResponse().setBody("""{"ref":"refs/heads/main"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        s.start()
        val owned = mutableSetOf<String>()
        val tool = AppBuilderTool(GitHubClient({ "t" }, base = s.url("/").toString()), { owned }, { owned += it }, { true })
        val r = tool.execute(buildJsonObject { put("action", JsonPrimitive("create")); put("app_name", JsonPrimitive("Tip Calc")) })
        val other = tool.execute(buildJsonObject {
            put("action", JsonPrimitive("write")); put("repo", JsonPrimitive("dom/secret-work"))
            put("files", buildJsonArray { add(buildJsonObject { put("path", JsonPrimitive("a.txt")); put("content", JsonPrimitive("x")) }) })
        })
        val escape = tool.execute(buildJsonObject {
            put("action", JsonPrimitive("write")); put("repo", JsonPrimitive("dom/tip-calc"))
            put("files", buildJsonArray { add(buildJsonObject { put("path", JsonPrimitive("../../x")); put("content", JsonPrimitive("x")) }) })
        })
        s.shutdown()
        assertFalse(r.output, r.isError)
        assertEquals(setOf("dom/tip-calc"), owned)
        assertTrue(r.output, r.output.contains("package com.truagent.tipcalc"))
        assertTrue(paths.contains("PATCH /repos/dom/tip-calc/git/refs/heads/main"))
        assertTrue(other.isError)
        assertTrue(other.output, other.output.contains("wasn't created by the app builder"))
        assertTrue(escape.isError)
    }
}

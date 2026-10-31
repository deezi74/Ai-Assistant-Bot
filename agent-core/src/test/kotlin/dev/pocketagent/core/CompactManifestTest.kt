package dev.pocketagent.core

import dev.pocketagent.core.agent.AgentMode
import dev.pocketagent.core.agent.Tool
import dev.pocketagent.core.agent.ToolRegistry
import dev.pocketagent.core.agent.ToolResult
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactManifestTest {
    private class Fake(override val name: String, override val description: String) : Tool {
        override val argumentsSignature = """{"q": string}"""
        override val modes = setOf(AgentMode.WIT, AgentMode.RESEARCH)
        override suspend fun execute(arguments: JsonObject) = ToolResult("ok")
    }

    private val registry = ToolRegistry(
        listOf(
            Fake("email", "Read the user's inbox. Never send without asking. Use folder INBOX by default."),
            Fake("make_video", "Make a short AI video. Takes a minute or two."),
            Fake("weather", "Current weather.")
        )
    )

    @Test
    fun compactKeepsOnlyFirstSentence() {
        val m = registry.compactManifest(AgentMode.WIT)
        assertTrue(m, m.contains("- email {\"q\": string}: Read the user's inbox."))
        assertFalse(m.contains("Never send"))
        assertEquals(3, m.lines().size)
    }

    @Test
    fun detailsOnlyForRelevantTools() {
        val d = registry.details(AgentMode.WIT, "check my unread mails please")
        assertTrue(d, d.contains("Never send without asking"))
        assertFalse(d.contains("make_video"))
        val v = registry.details(AgentMode.WIT, "make me a video of a cat surfing")
        assertTrue(v, v.contains("make_video"))
        assertFalse(v.contains("email"))
        // One-sentence descriptions are already complete in the compact list.
        assertEquals("", registry.details(AgentMode.WIT, "what's the weather"))
        assertEquals("", registry.details(AgentMode.WIT, "hello there"))
    }

    @Test
    fun firstSentenceHandlesNoPeriod() {
        assertEquals("No period here", ToolRegistry.firstSentence("No period here"))
    }
}

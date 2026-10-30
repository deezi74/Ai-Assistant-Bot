package dev.pocketagent.core

import dev.pocketagent.core.agent.StreamSegment
import dev.pocketagent.core.agent.ToolCallParser
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallParserTest {

    private fun feedAll(chunks: List<String>): List<StreamSegment> {
        val p = ToolCallParser()
        val out = ArrayList<StreamSegment>()
        chunks.forEach { out += p.feed(it) }
        out += p.finish()
        return out
    }

    @Test
    fun plainTextPassesThrough() {
        val segs = feedAll(listOf("Hello ", "world"))
        assertEquals("Hello world", segs.filterIsInstance<StreamSegment.Text>().joinToString("") { it.text })
    }

    @Test
    fun toolCallSplitAcrossTokensIsDetected() {
        val chunks = listOf("Let me check.", "<to", "ol_c", "all>{\"name\": \"web_search\", ", "\"arguments\": {\"query\": \"kotlin 2.1\"}}", "</tool", "_call>", "ignored")
        val segs = feedAll(chunks)
        val text = segs.filterIsInstance<StreamSegment.Text>().joinToString("") { it.text }
        assertTrue("tag must not leak into text: $text", !text.contains("<"))
        val call = segs.filterIsInstance<StreamSegment.Call>().single().call
        assertEquals("web_search", call.name)
        assertEquals("kotlin 2.1", call.arguments["query"]!!.jsonPrimitive.content)
    }

    @Test
    fun argumentsAsJsonStringAreDecoded() {
        val seg = ToolCallParser.parseCall("{\"name\": \"fetch_page\", \"arguments\": \"{\\\"url\\\": \\\"example.com\\\"}\"}")
        val call = (seg as StreamSegment.Call).call
        assertEquals("example.com", call.arguments["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun thinkBlocksBecomeThoughts() {
        val segs = feedAll(listOf("<thi", "nk>planning", " stuff</th", "ink>Answer"))
        assertEquals("planning stuff", segs.filterIsInstance<StreamSegment.Thought>().joinToString("") { it.text })
        assertEquals("Answer", segs.filterIsInstance<StreamSegment.Text>().joinToString("") { it.text })
    }

    @Test
    fun unterminatedCallAtEndOfStreamIsAccepted() {
        val segs = feedAll(listOf("<tool_call>{\"name\":\"current_time\",\"arguments\":{}}"))
        assertEquals("current_time", segs.filterIsInstance<StreamSegment.Call>().single().call.name)
    }

    @Test
    fun malformedJsonIsReported() {
        val segs = feedAll(listOf("<tool_call>not json</tool_call>"))
        assertTrue(segs.single() is StreamSegment.MalformedCall)
    }

    @Test
    fun lessThanSignInNormalTextIsEmittedAtFinish() {
        val segs = feedAll(listOf("a < b and 3 <"))
        assertEquals("a < b and 3 <", segs.filterIsInstance<StreamSegment.Text>().joinToString("") { it.text })
    }
}

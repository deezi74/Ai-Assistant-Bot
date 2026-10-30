package dev.pocketagent.core

import dev.pocketagent.core.agent.AgentEvent
import dev.pocketagent.core.agent.AgentLoop
import dev.pocketagent.core.agent.AgentMode
import dev.pocketagent.core.agent.PersonaManager
import dev.pocketagent.core.agent.Tool
import dev.pocketagent.core.agent.ToolRegistry
import dev.pocketagent.core.agent.ToolResult
import dev.pocketagent.core.agent.string
import dev.pocketagent.core.llm.ChatMessage
import dev.pocketagent.core.llm.ChatRole
import dev.pocketagent.core.llm.FinishReason
import dev.pocketagent.core.llm.GenerationParams
import dev.pocketagent.core.llm.LLMProvider
import dev.pocketagent.core.llm.LlmEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Scripted provider: each call replays the next script as single-character tokens. */
private class ScriptedProvider(private val scripts: List<String>) : LLMProvider {
    val seen = ArrayList<List<ChatMessage>>()
    override val id = "fake"
    override val displayName = "Fake"
    override val supportsVision = false
    override suspend fun isReady() = true
    override suspend fun release() = Unit

    override fun stream(messages: List<ChatMessage>, params: GenerationParams): Flow<LlmEvent> = flow {
        val script = scripts[seen.size]
        seen += messages.toList()
        for (ch in script) emit(LlmEvent.Token(ch.toString()))
        emit(LlmEvent.Done(FinishReason.STOP, script.length))
    }
}

private class EchoTool : Tool {
    var calls = 0
    override val name = "echo"
    override val description = "echo"
    override val argumentsSignature = """{"text": string}"""
    override val modes = setOf(AgentMode.WIT, AgentMode.RESEARCH)
    override suspend fun execute(arguments: JsonObject): ToolResult {
        calls++
        return ToolResult("ECHO<${arguments.string("text")}>")
    }
}

class AgentLoopTest {
    private fun persona() = PersonaManager(Files.createTempDirectory("soul").toFile())

    @Test
    fun runsToolThenAnswers() = runTest {
        val provider = ScriptedProvider(
            listOf(
                "<think>need echo</think>Checking.<tool_call>{\"name\":\"echo\",\"arguments\":{\"text\":\"hi\"}}</tool_call>TRAILING GARBAGE",
                "Final: hi back"
            )
        )
        val tool = EchoTool()
        val events = AgentLoop(provider, ToolRegistry(listOf(tool)), persona()).run(emptyList(), "say hi", AgentMode.WIT).toList()

        assertEquals(1, tool.calls)
        val completed = events.filterIsInstance<AgentEvent.Completed>().single()
        assertEquals("Final: hi back", completed.answer)
        assertEquals(2, completed.steps)
        assertTrue(events.any { it is AgentEvent.ThoughtDelta })

        // Second request carries the assistant tool call and the verbatim observation.
        val second = provider.seen[1]
        assertEquals(ChatRole.ASSISTANT, second[second.size - 2].role)
        assertTrue(second[second.size - 2].content.endsWith("</tool_call>"))
        assertTrue(!second[second.size - 2].content.contains("TRAILING"))
        val observation = second.last()
        assertEquals(ChatRole.USER, observation.role)
        assertTrue(observation.content.startsWith("[Observation] echo"))
        assertTrue(observation.content.contains("ECHO<hi>"))
        assertTrue(second.first().content.contains("echo"))
    }

    @Test
    fun unknownToolProducesErrorObservation() = runTest {
        val provider = ScriptedProvider(
            listOf("<tool_call>{\"name\":\"nope\",\"arguments\":{}}</tool_call>", "ok")
        )
        val events = AgentLoop(provider, ToolRegistry(), persona()).run(emptyList(), "x", AgentMode.WIT).toList()
        val obs = events.filterIsInstance<AgentEvent.Observation>().single()
        assertTrue(obs.isError)
        assertEquals("ok", events.filterIsInstance<AgentEvent.Completed>().single().answer)
    }

    @Test
    fun providerFailureIsSurfaced() = runTest {
        val failing = object : LLMProvider {
            override val id = "f"
            override val displayName = "F"
            override val supportsVision = false
            override suspend fun isReady() = true
            override suspend fun release() = Unit
            override fun stream(messages: List<ChatMessage>, params: GenerationParams) = flow {
                emit(LlmEvent.Failure("boom"))
                emit(LlmEvent.Done(FinishReason.ERROR, 0))
            }
        }
        val events = AgentLoop(failing, ToolRegistry(), persona()).run(emptyList(), "x", AgentMode.WIT).toList()
        assertEquals("boom", events.filterIsInstance<AgentEvent.Failed>().single().message)
    }
}

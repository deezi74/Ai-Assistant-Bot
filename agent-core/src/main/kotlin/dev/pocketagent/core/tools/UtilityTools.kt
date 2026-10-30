package dev.pocketagent.core.tools

import dev.pocketagent.core.agent.AgentMode
import dev.pocketagent.core.agent.Tool
import dev.pocketagent.core.agent.ToolResult
import kotlinx.serialization.json.JsonObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class CurrentTimeTool(private val clock: () -> ZonedDateTime = { ZonedDateTime.now() }) : Tool {
    override val name = "current_time"
    override val description = "Get the current local date, time and time zone."
    override val argumentsSignature = "{}"
    override val modes = setOf(AgentMode.WIT, AgentMode.RESEARCH)

    override suspend fun execute(arguments: JsonObject): ToolResult =
        ToolResult(clock().format(DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm:ss zzz (xxx)")))
}

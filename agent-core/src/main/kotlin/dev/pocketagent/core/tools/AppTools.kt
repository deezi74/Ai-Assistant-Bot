package dev.pocketagent.core.tools

import dev.pocketagent.core.agent.AgentMode
import dev.pocketagent.core.agent.Tool
import dev.pocketagent.core.agent.ToolResult
import dev.pocketagent.core.agent.string
import kotlinx.serialization.json.JsonObject

private val bothModes = setOf(AgentMode.WIT, AgentMode.RESEARCH)

/** Saves a document to the Library. [save] returns the stored file name. */
class SaveArtifactTool(private val save: suspend (title: String, content: String, extension: String) -> String) : Tool {
    override val name = "save_artifact"
    override val description = "Save a document (notes, plan, table, code, itinerary) to the user's Library so they can open it later."
    override val argumentsSignature = """{"title": string, "content": string (markdown), "format"?: "md"|"txt"|"csv"|"html"|"json"}"""
    override val modes = bothModes

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val title = arguments.string("title") ?: return ToolResult.error("missing \"title\"")
        val content = arguments.string("content") ?: arguments.string("body") ?: return ToolResult.error("missing \"content\"")
        val ext = (arguments.string("format") ?: "md").lowercase().filter { it.isLetterOrDigit() }.take(5).ifEmpty { "md" }
        val stored = save(title, content, ext)
        return ToolResult("Saved to Library as $stored (${content.length} characters).")
    }
}

/** Appends a durable fact about the user to memory.md. */
class RememberTool(private val append: suspend (fact: String) -> Unit) : Tool {
    override val name = "remember"
    override val description = "Store a lasting fact about the user (preferences, people, projects) in long-term memory. Only for things worth recalling in future chats."
    override val argumentsSignature = """{"fact": string}"""
    override val modes = bothModes

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val fact = arguments.string("fact") ?: arguments.string("input") ?: return ToolResult.error("missing \"fact\"")
        append(fact.trim())
        return ToolResult("Remembered.")
    }
}

/** Adds an item to the Goals ▸ Tracking list. */
class TrackGoalTool(private val add: suspend (title: String, note: String, category: String) -> Unit) : Tool {
    override val name = "track_goal"
    override val description = "Add a goal or follow-up to the user's Tracking list once the user has agreed on it."
    override val argumentsSignature = """{"title": string, "note"?: string, "category"?: "Health"|"Relationships"|"Finance"|"Career"|"Interests"|"Productivity"|"Other"}"""
    override val modes = bothModes

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val title = arguments.string("title") ?: return ToolResult.error("missing \"title\"")
        add(title, arguments.string("note").orEmpty(), arguments.string("category") ?: "Other")
        return ToolResult("Added \"$title\" to Tracking.")
    }
}

package dev.pocketagent.core.agent

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Wit = the bold, funny persona. Research = sober, thorough, citation-heavy. Both get live web tools. */
enum class AgentMode(val label: String) {
    WIT("Wit"),
    RESEARCH("Research")
}

data class ToolResult(
    val output: String,
    val imageJpegBase64: String? = null,
    val isError: Boolean = false
) {
    companion object {
        fun error(message: String) = ToolResult("ERROR: $message", isError = true)
    }
}

interface Tool {
    val name: String
    val description: String

    /** Human/LLM-readable argument signature, e.g. `{"query": string, "max_results"?: int}`. */
    val argumentsSignature: String

    val modes: Set<AgentMode>

    /** Tools that are registered but currently unusable (service off, no permission) report false. */
    fun isAvailable(): Boolean = true

    suspend fun execute(arguments: JsonObject): ToolResult
}

class ToolRegistry(tools: List<Tool> = emptyList()) {
    private val byName = LinkedHashMap<String, Tool>()

    init {
        tools.forEach { register(it) }
    }

    fun register(tool: Tool) {
        byName[tool.name] = tool
    }

    fun get(name: String): Tool? = byName[name]

    fun forMode(mode: AgentMode): List<Tool> = byName.values.filter { mode in it.modes && it.isAvailable() }

    /** Manifest text injected into the system prompt. */
    fun manifest(mode: AgentMode): String = forMode(mode).joinToString("\n") { tool ->
        "- ${tool.name}: ${tool.description}\n  arguments: ${tool.argumentsSignature}"
    }
}

// Argument helpers shared by tool implementations.

fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

fun JsonObject.int(key: String): Int? {
    val p = this[key] as? JsonPrimitive ?: return null
    return p.intOrNull ?: p.doubleOrNull?.toInt() ?: p.contentOrNull?.trim()?.toIntOrNull()
}

fun JsonObject.long(key: String): Long? {
    val p = this[key] as? JsonPrimitive ?: return null
    return p.longOrNull ?: p.doubleOrNull?.toLong() ?: p.contentOrNull?.trim()?.toLongOrNull()
}

fun JsonObject.float(key: String): Float? {
    val p = this[key] as? JsonPrimitive ?: return null
    return p.doubleOrNull?.toFloat() ?: p.contentOrNull?.trim()?.toFloatOrNull()
}

fun JsonObject.bool(key: String): Boolean? {
    val p = this[key] as? JsonPrimitive ?: return null
    return p.booleanOrNull ?: when (p.contentOrNull?.trim()?.lowercase()) {
        "true", "yes", "1" -> true
        "false", "no", "0" -> false
        else -> null
    }
}

fun JsonElement?.asPrimitiveString(): String? = (this as? JsonPrimitive)?.contentOrNull

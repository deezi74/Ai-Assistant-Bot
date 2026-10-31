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

    /** Overrides the loop's tool timeout for slow tools (image and video generation). */
    val timeoutMs: Long? get() = null

    /** Words in a request that mean this tool's full instructions should be in the prompt. */
    val keywords: List<String> get() = name.split('_').filter { it.length > 2 && it !in GENERIC_WORDS }

    suspend fun execute(arguments: JsonObject): ToolResult
}

private val GENERIC_WORDS = setOf("make", "run", "save", "track", "change", "learn", "current", "fetch", "shared", "builder")

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

    /** Full manifest text: every tool with its whole description. */
    fun manifest(mode: AgentMode): String = forMode(mode).joinToString("\n") { tool ->
        "- ${tool.name}: ${tool.description}\n  arguments: ${tool.argumentsSignature}"
    }

    /**
     * Short manifest for speed: one line per tool (name, arguments, first sentence).
     * It never depends on the request, so an on-device model can keep it in its
     * KV cache; [details] adds the full text for the tools a request needs.
     */
    fun compactManifest(mode: AgentMode): String = forMode(mode).joinToString("\n") { tool ->
        "- ${tool.name} ${tool.argumentsSignature}: ${firstSentence(tool.description)}"
    }

    /** Full descriptions of the tools [request] seems to need, plus the [always] ones; blank when none. */
    fun details(mode: AgentMode, request: String, always: Set<String> = emptySet()): String {
        val text = request.lowercase()
        val words = Regex("[a-z0-9]+").findAll(text).map { it.value }.toSet()
        return forMode(mode)
            .filter { tool ->
                tool.name in always || text.contains(tool.name) ||
                    (tool.keywords + KEYWORDS[tool.name].orEmpty()).any { k -> val key = k.lowercase(); if (' ' in key) text.contains(key) else key in words || words.any { w -> w.length > 3 && w.startsWith(key) } }
            }
            .filter { firstSentence(it.description).length < it.description.length }
            .joinToString("\n") { "- ${it.name}: ${it.description}" }
    }

    companion object {
        /** Extra trigger words for the built-in tools (prefix match, so "photo" also covers "photos"). */
        val KEYWORDS: Map<String, List<String>> = mapOf(
            "email" to listOf("mail", "inbox", "gmail", "unread", "outlook"),
            "phone" to listOf("call", "text", "sms", "message", "alarm", "timer", "contact", "flashlight", "volume", "wifi", "bluetooth", "open"),
            "termux" to listOf("terminal", "shell", "command", "npm", "freellmapi", "linux", "build"),
            "shared_files" to listOf("file", "folder", "download", "document", "pdf"),
            "learn_skill" to listOf("skill", "learn", "teach"),
            "change_character" to listOf("avatar", "character", "look"),
            "imagine" to listOf("image", "picture", "photo", "draw", "paint", "art", "logo", "wallpaper", "edit", "generate", "selfie", "poster", "sticker"),
            "make_video" to listOf("video", "clip", "animate", "animation", "movie", "film", "reel", "tiktok"),
            "app_builder" to listOf("app", "apk", "android", "build"),
            "github" to listOf("repo", "issue", "pull", "commit", "code"),
            "location" to listOf("where", "location", "address", "near"),
            "places" to listOf("near", "restaurant", "food", "store", "shop", "open", "directions", "place", "gas", "coffee"),
            "weather" to listOf("weather", "rain", "forecast", "temperature", "hot", "cold", "snow"),
            "computer" to listOf("program", "script", "code", "javascript", "file", "computer"),
            "run_code" to listOf("calculate", "compute", "code", "javascript", "math"),
            "save_artifact" to listOf("save", "write", "document", "report", "export", "note"),
            "remember" to listOf("remember", "memory", "forget", "note"),
            "track_goal" to listOf("goal", "habit", "track", "plan"),
            "calendar" to listOf("calendar", "event", "meeting", "schedule", "appointment", "tomorrow", "today"),
            "drive" to listOf("drive", "doc", "sheet", "slides", "spreadsheet"),
            "fetch_page" to listOf("http", "www", "link", "url", "page", "article")
        )

        fun firstSentence(text: String): String {
            val t = text.trim()
            val cut = Regex("[.!?](\\s|$)").find(t)?.range?.first ?: return t
            return t.substring(0, cut + 1)
        }
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

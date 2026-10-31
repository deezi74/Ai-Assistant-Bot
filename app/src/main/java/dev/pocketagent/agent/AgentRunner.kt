package dev.pocketagent.agent

import dev.pocketagent.AppContainer
import dev.pocketagent.core.agent.AgentConfig
import dev.pocketagent.core.agent.AgentEvent
import dev.pocketagent.core.agent.AgentLoop
import dev.pocketagent.core.agent.AgentMode
import dev.pocketagent.core.llm.GenerationParams
import dev.pocketagent.data.TokenUsageEntity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds agent loops with the app's context (name, memory, skills, goals, env vars) and records usage. */
class AgentRunner(private val container: AppContainer) {

    fun newLoop(maxSteps: Int? = null, maxTokens: Int? = null): AgentLoop {
        val s = container.settings.current
        return AgentLoop(
            provider = container.scheduler,
            tools = container.tools,
            persona = container.persona,
            config = AgentConfig(
                maxSteps = maxSteps ?: s.maxSteps,
                generation = GenerationParams(maxTokens = maxTokens ?: s.maxTokens, temperature = s.temperature)
            ),
            argumentTransformer = ::expandEnv
        )
    }

    /**
     * App-owned system prompt sections, each with a character budget so a growing
     * memory or skill list cannot slow every reply down.
     */
    suspend fun extraContext(mode: AgentMode = container.settings.current.mode): String {
        val s = container.settings.current
        val memory = container.workspace.readMemory().trim()
        val skills = container.workspace.skills().filter { it.enabled }
        val goals = container.database.content().openGoals()
        return buildString {
            append("## Identity\nYour name is ").append(s.assistantName.ifBlank { "Nova" }).append(". Answer to that name.\n")
            if (s.userName.isNotBlank()) append("The user's name is ").append(s.userName).append(".\n")
            if (s.avatarKind == "photo" && s.characterDescription.isNotBlank()) {
                append("Your look (an AI-generated character the user chose): ").append(s.characterDescription.take(300))
                    .append(". Let it flavour your personality when that fits.\n")
            }
            append("You have your own computer on this phone (the computer tool): build, save and run JavaScript programs there.\n")
            if (s.offlineMode) append("Offline mode is on: web tools are disabled, answer from what you know.\n")
            if (memory.isNotEmpty()) {
                append("\n## Memory (facts about the user; use the remember tool to add more)\n")
                append(newestLines(memory, MEMORY_BUDGET)).append('\n')
            }
            if (skills.isNotEmpty()) {
                append("\n## Skills\n")
                var used = 0
                for (skill in skills) {
                    val body = skill.instructions.trim().let { if (it.length > SKILL_BUDGET) it.take(SKILL_BUDGET) + "…" else it }
                    if (used + body.length > SKILLS_TOTAL_BUDGET) break
                    used += body.length
                    append("### ").append(skill.name).append('\n').append(body).append("\n\n")
                }
            }
            append(container.learner.promptSection())
            if (goals.isNotEmpty()) {
                append("\n## Goals the user is tracking\n")
                goals.take(10).forEach { g ->
                    append("- [").append(g.category).append("] ").append(g.title)
                    if (g.note.isNotBlank()) append(": ").append(g.note.take(160))
                    append('\n')
                }
            }
            if (s.quickReplies && mode == AgentMode.WIT && usesQwen3()) {
                // Qwen3's soft switch: answer straight away instead of a long hidden reasoning pass.
                append("\n/no_think\n")
            }
        }
    }

    private fun usesQwen3(): Boolean {
        val local = (container.localEngine.state.value as? dev.pocketagent.llm.LocalModelState.Ready)?.fileName.orEmpty()
        val remote = container.settings.current.remoteModel
        return local.contains("qwen3", ignoreCase = true) || remote.contains("qwen3", ignoreCase = true)
    }

    /** Keeps the most recent lines of a markdown list within [budget] characters. */
    private fun newestLines(text: String, budget: Int): String {
        if (text.length <= budget) return text
        val kept = ArrayDeque<String>()
        var size = 0
        for (line in text.lines().asReversed()) {
            if (line.isBlank() || line.startsWith("#")) continue
            if (size + line.length + 1 > budget) break
            kept.addFirst(line)
            size += line.length + 1
        }
        return kept.joinToString("\n")
    }

    /** Runs a prompt headlessly (routines, feed) and returns the final answer. */
    suspend fun runOnce(prompt: String, mode: AgentMode = AgentMode.RESEARCH, maxSteps: Int = 8): String {
        var answer = ""
        var failure: String? = null
        newLoop(maxSteps = maxSteps, maxTokens = 1536).run(emptyList(), prompt, mode, extraContext(mode)).collect { event ->
            when (event) {
                is AgentEvent.Completed -> answer = event.answer
                is AgentEvent.Failed -> failure = event.message
                is AgentEvent.Usage -> recordUsage(event)
                is AgentEvent.ToolCallStarted -> container.workspace.log("I", "routine tool ${event.name} ${event.argumentsJson.take(200)}")
                else -> Unit
            }
        }
        failure?.let { throw IllegalStateException(it) }
        return answer
    }

    suspend fun recordUsage(event: AgentEvent.Usage) {
        if (event.promptTokens <= 0 && event.completionTokens <= 0) return
        val dao = container.database.content()
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val provider = event.provider.substringBefore(" · ").ifBlank { event.provider }
        val existing = dao.usage(day, provider)
        dao.putUsage(
            TokenUsageEntity(
                day = day,
                provider = provider,
                promptTokens = (existing?.promptTokens ?: 0L) + event.promptTokens,
                completionTokens = (existing?.completionTokens ?: 0L) + event.completionTokens,
                requests = (existing?.requests ?: 0L) + 1
            )
        )
    }

    /** Replaces $NAME / ${NAME} in string arguments with values from Settings ▸ Environment variables. */
    fun expandEnv(arguments: JsonObject): JsonObject {
        val env = container.settings.envVars
        if (env.isEmpty()) return arguments
        val pattern = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)}|\$([A-Za-z_][A-Za-z0-9_]*)""")
        fun expand(el: JsonElement): JsonElement = when {
            el is JsonPrimitive && el.isString -> JsonPrimitive(
                pattern.replace(el.content) { m ->
                    val key = m.groupValues[1].ifEmpty { m.groupValues[2] }
                    env[key] ?: m.value
                }
            )
            el is JsonObject -> JsonObject(el.mapValues { expand(it.value) })
            else -> el
        }
        return JsonObject(arguments.mapValues { expand(it.value) })
    }

    private companion object {
        const val MEMORY_BUDGET = 2_500
        const val SKILL_BUDGET = 700
        const val SKILLS_TOTAL_BUDGET = 3_500
    }
}

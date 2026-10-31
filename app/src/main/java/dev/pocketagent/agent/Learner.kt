package dev.pocketagent.agent

import dev.pocketagent.AppContainer
import dev.pocketagent.core.llm.ChatMessage
import dev.pocketagent.core.llm.ChatRole
import dev.pocketagent.core.llm.GenerationParams
import dev.pocketagent.core.llm.LlmEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

data class Growth(
    val xp: Int = 0,
    val tasks: Int = 0,
    val toolCalls: Int = 0,
    val lessons: Int = 0,
    val facts: Int = 0,
    val skills: Int = 0,
    val programs: Int = 0
) {
    val level: Int get() = 1 + sqrt(xp / XP_PER_LEVEL_UNIT).toInt()
    val levelStartXp: Int get() = ((level - 1) * (level - 1) * XP_PER_LEVEL_UNIT).toInt()
    val nextLevelXp: Int get() = (level * level * XP_PER_LEVEL_UNIT).toInt()
    val progress: Float get() = ((xp - levelStartXp).toFloat() / (nextLevelXp - levelStartXp).coerceAtLeast(1)).coerceIn(0f, 1f)

    val title: String get() = when {
        level >= 20 -> "Legend"
        level >= 12 -> "Master"
        level >= 8 -> "Expert"
        level >= 5 -> "Specialist"
        level >= 3 -> "Helper"
        level >= 2 -> "Apprentice"
        else -> "Hatchling"
    }

    companion object {
        const val XP_PER_LEVEL_UNIT = 25.0
    }
}

data class Lesson(val text: String, val date: String)

/** What one reflection saved, shown as a small note under the answer. */
data class Learned(val facts: List<String>, val lesson: String?, val skill: String?) {
    val isEmpty: Boolean get() = facts.isEmpty() && lesson == null && skill == null

    fun summary(): String = buildList {
        if (facts.isNotEmpty()) add(if (facts.size == 1) "1 thing about you" else "${facts.size} things about you")
        if (lesson != null) add("a lesson")
        if (skill != null) add("the skill \"$skill\"")
    }.joinToString(", ")
}

/**
 * How the assistant learns as it grows: it earns XP for finished tasks, and after a
 * task it reflects on the exchange to save lasting facts about the user, a lesson
 * for next time and, when it worked out a repeatable procedure, a new skill.
 * Everything it learns is plain text the user can read and delete.
 */
class Learner(private val container: AppContainer) {
    private val workspace get() = container.workspace

    private val _growth = MutableStateFlow(readGrowth())
    val growth: StateFlow<Growth> = _growth.asStateFlow()

    private val _lessons = MutableStateFlow(readLessons())
    val lessons: StateFlow<List<Lesson>> = _lessons.asStateFlow()

    /** Records a finished task and returns the new growth. */
    @Synchronized
    fun recordTask(toolCalls: Int, usedComputer: Boolean): Growth {
        val g = _growth.value
        val next = g.copy(
            xp = g.xp + 10 + 4 * toolCalls.coerceAtMost(10) + if (usedComputer) 6 else 0,
            tasks = g.tasks + 1,
            toolCalls = g.toolCalls + toolCalls,
            programs = g.programs + if (usedComputer) 1 else 0
        )
        save(next)
        return next
    }

    @Synchronized
    fun recordSkill(): Growth {
        val g = _growth.value
        return g.copy(xp = g.xp + 15, skills = g.skills + 1).also { save(it) }
    }

    /** True when a finished task is worth a reflection pass. */
    fun shouldReflect(prompt: String, answer: String, toolCalls: Int): Boolean {
        if (!container.settings.current.autoLearn || answer.isBlank()) return false
        if (toolCalls > 0) return true
        val words = prompt.split(Regex("\\s+")).count { it.isNotEmpty() }
        return words >= 6 || PERSONAL.containsMatchIn(prompt)
    }

    /**
     * Asks the active model what is worth keeping from this exchange and saves it.
     * Returns what was learned, or null if the model was busy or gave nothing usable.
     */
    suspend fun reflect(prompt: String, answer: String, tools: List<String>): Learned? {
        val memory = workspace.readMemory()
        val known = _lessons.value.takeLast(15).joinToString("\n") { "- " + it.text }
        val skills = workspace.skills().joinToString(", ") { it.name }
        val transcript = buildString {
            append("USER: ").append(prompt.take(1500)).append("\n\n")
            if (tools.isNotEmpty()) append("TOOLS USED: ").append(tools.joinToString(", ")).append("\n\n")
            append("ASSISTANT: ").append(answer.take(2500))
        }
        val system = """
            You review one finished conversation turn of a personal assistant so it gets better over time.
            Reply with exactly these three lines and nothing else:
            FACT: <one lasting fact about the user worth remembering for future chats, or NONE>
            LESSON: <one short, general lesson for doing this kind of task better next time, or NONE>
            SKILL: <name> | <when to use it> | <numbered steps to repeat this task> (only for a multi-step task likely to recur, otherwise NONE)
            Rules: never invent facts; skip anything already known; no secrets, passwords or payment details; keep each line under 300 characters.
            Already known facts:
            ${memory.takeLast(1500).ifBlank { "(none)" }}
            Already known lessons:
            ${known.ifBlank { "(none)" }}
            Existing skills: ${skills.ifBlank { "(none)" }}
        """.trimIndent()

        val raw = withTimeoutOrNull(REFLECT_TIMEOUT_MS) {
            val text = StringBuilder()
            container.scheduler.stream(
                listOf(ChatMessage(ChatRole.SYSTEM, system), ChatMessage(ChatRole.USER, transcript)),
                GenerationParams(maxTokens = 320, temperature = 0.2f)
            ).collect { event ->
                when (event) {
                    is LlmEvent.Token -> text.append(event.text)
                    is LlmEvent.Failure -> throw IllegalStateException(event.message)
                    else -> Unit
                }
            }
            text.toString()
        } ?: return null

        val parsed = parse(raw)
        return withContext(Dispatchers.IO) { store(parsed, memory) }
    }

    private data class Parsed(val facts: List<String>, val lesson: String?, val skill: Triple<String, String, String>?)

    private fun parse(raw: String): Parsed {
        val text = raw.replace(Regex("(?s)<think>.*?</think>"), "").replace("**", "")
        val facts = mutableListOf<String>()
        var lesson: String? = null
        var skill: Triple<String, String, String>? = null
        text.lines().map { it.trim().trimStart('-', '*', ' ') }.forEach { line ->
            val key = line.substringBefore(':').trim().uppercase(Locale.US)
            val value = line.substringAfter(':', "").trim().trim('"')
            if (value.isEmpty() || value.equals("NONE", true) || value.startsWith("NONE", true) || value.startsWith("<")) return@forEach
            when (key) {
                "FACT" -> if (facts.size < 2) facts += value.take(300)
                "LESSON" -> if (lesson == null) lesson = value.take(300)
                "SKILL" -> if (skill == null) {
                    val parts = value.split('|').map { it.trim() }
                    if (parts.size >= 3 && parts[0].length in 3..48 && parts[2].length >= 20) {
                        skill = Triple(parts[0], parts[1].take(160), parts.drop(2).joinToString(" | ").take(1500))
                    }
                }
            }
        }
        return Parsed(facts, lesson, skill)
    }

    private fun store(p: Parsed, memory: String): Learned {
        val newFacts = p.facts.filter { f -> !looksKnown(f, memory.lines()) && !SENSITIVE.containsMatchIn(f) }
        newFacts.forEach { workspace.appendMemory(it) }

        val lesson = p.lesson?.takeIf { l -> !looksKnown(l, _lessons.value.map { it.text }) && !SENSITIVE.containsMatchIn(l) }
        if (lesson != null) addLesson(lesson)

        val skillName = p.skill?.let { (name, whenToUse, steps) ->
            val existing = workspace.skills().map { it.name.lowercase(Locale.US) }
            if (name.lowercase(Locale.US) in existing) null else {
                workspace.saveSkill(null, name, "Learned: $whenToUse", "Use when: $whenToUse\n\n$steps", true)
                name
            }
        }

        val g = _growth.value
        save(
            g.copy(
                xp = g.xp + 3 * newFacts.size + (if (lesson != null) 5 else 0) + (if (skillName != null) 15 else 0),
                facts = g.facts + newFacts.size,
                lessons = g.lessons + if (lesson != null) 1 else 0,
                skills = g.skills + if (skillName != null) 1 else 0
            )
        )
        if (newFacts.isNotEmpty() || lesson != null || skillName != null) {
            workspace.log("I", "learned: ${newFacts.size} fact(s), lesson=${lesson != null}, skill=${skillName ?: "-"}")
        }
        return Learned(newFacts, lesson, skillName)
    }

    // region Lessons

    @Synchronized
    fun addLesson(text: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val list = (_lessons.value + Lesson(text.replace('\n', ' ').trim(), stamp)).takeLast(MAX_LESSONS)
        writeLessons(list)
    }

    @Synchronized
    fun deleteLesson(lesson: Lesson) = writeLessons(_lessons.value - lesson)

    @Synchronized
    fun clearLessons() = writeLessons(emptyList())

    private fun writeLessons(list: List<Lesson>) {
        workspace.lessonsFile.writeText(
            "# Lessons learned\n\n" + list.joinToString("") { "- ${it.text} (${it.date})\n" }
        )
        _lessons.value = list
    }

    private fun readLessons(): List<Lesson> {
        val f = workspace.lessonsFile
        if (!f.isFile) return emptyList()
        val pattern = Regex("""^- (.*) \((\d{4}-\d{2}-\d{2})\)$""")
        return f.readLines().mapNotNull { line ->
            pattern.find(line.trim())?.let { Lesson(it.groupValues[1], it.groupValues[2]) }
                ?: line.trim().removePrefix("- ").takeIf { line.trim().startsWith("- ") && it.isNotBlank() }?.let { Lesson(it, "") }
        }
    }

    // endregion

    // region Growth file

    @Synchronized
    fun resetGrowth() = save(Growth())

    private fun save(g: Growth) {
        _growth.value = g
        try {
            workspace.growthFile.writeText(
                JSONObject()
                    .put("xp", g.xp).put("tasks", g.tasks).put("tool_calls", g.toolCalls)
                    .put("lessons", g.lessons).put("facts", g.facts).put("skills", g.skills).put("programs", g.programs)
                    .toString()
            )
        } catch (e: java.io.IOException) {
            workspace.log("W", "could not save growth: ${e.message}")
        }
    }

    private fun readGrowth(): Growth {
        val f = workspace.growthFile
        if (!f.isFile) return Growth()
        return try {
            val o = JSONObject(f.readText())
            Growth(
                o.optInt("xp"), o.optInt("tasks"), o.optInt("tool_calls"), o.optInt("lessons"),
                o.optInt("facts"), o.optInt("skills"), o.optInt("programs")
            )
        } catch (e: org.json.JSONException) {
            Growth()
        }
    }

    // endregion

    /** Context block for the system prompt. */
    fun promptSection(): String {
        val g = _growth.value
        val lessons = _lessons.value.takeLast(12)
        return buildString {
            append("\n## Growth\nYou are level ").append(g.level).append(" (").append(g.title).append("), with ")
                .append(g.tasks).append(" tasks done. You learn from experience: apply the lessons below, and when you work out a ")
                .append("repeatable procedure, save it with the learn_skill tool.\n")
            if (lessons.isNotEmpty()) {
                append("\n## Lessons you have learned\n")
                lessons.forEach { append("- ").append(it.text).append('\n') }
            }
        }
    }

    private fun looksKnown(candidate: String, known: List<String>): Boolean {
        val words = tokens(candidate)
        if (words.isEmpty()) return true
        return known.any { line ->
            val other = tokens(line)
            other.isNotEmpty() && words.intersect(other).size.toDouble() / words.size >= 0.7
        }
    }

    private fun tokens(s: String): Set<String> =
        s.lowercase(Locale.US).split(Regex("[^a-z0-9]+")).filter { it.length > 2 && it !in STOP }.toSet()

    private companion object {
        const val REFLECT_TIMEOUT_MS = 90_000L
        const val MAX_LESSONS = 60
        val PERSONAL = Regex("\\b(i am|i'm|my|i like|i love|i hate|i prefer|i work|call me|remember)\\b", RegexOption.IGNORE_CASE)
        val SENSITIVE = Regex("password|passcode|\\bpin\\b|credit card|card number|cvv|social security|\\bssn\\b|api key|secret key|token", RegexOption.IGNORE_CASE)
        val STOP = setOf("the", "and", "for", "with", "that", "this", "user", "users", "they", "them", "their", "are", "was", "has", "have", "likes", "wants")
    }
}

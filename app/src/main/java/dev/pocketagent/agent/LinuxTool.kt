package dev.pocketagent.agent

import dev.pocketagent.core.agent.AgentMode
import dev.pocketagent.core.agent.Tool
import dev.pocketagent.core.agent.ToolResult
import dev.pocketagent.core.agent.int
import dev.pocketagent.core.agent.string
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/** Gives the agent the built-in Alpine Linux: shell, packages (apk), Python, Node, git, curl. */
class LinuxTool(private val linux: LinuxEnvironment) : Tool {
    override val name = "linux"
    override val description =
        "Your own Linux computer inside the app (Alpine Linux, you are root, internet works, packages with `apk add`; python3, node, git and curl are installed). " +
            "run: a shell command (up to 5 min). start: a long command or server in the background, returns a job id; job: its log and status; stop: end it; jobs: list. " +
            "write / read: a file (paths relative to /root). Use it to run code, scripts, scrapers, data work and servers. " +
            "It cannot see the phone's own files or apps."
    override val argumentsSignature =
        """{"action": "run"|"start"|"job"|"stop"|"jobs"|"write"|"read", "command"?: string, "id"?: string, "path"?: string, "content"?: string, "timeout"?: int}"""
    override val modes = setOf(AgentMode.WIT, AgentMode.RESEARCH)
    override val timeoutMs: Long = 6 * 60_000L
    override val keywords: List<String> = listOf("linux", "shell", "terminal", "bash", "python", "script", "server", "install", "package", "node", "npm", "pip", "git", "curl", "command")
    override fun isAvailable(): Boolean = linux.installed

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val action = arguments.string("action")?.lowercase(Locale.US) ?: "run"
        return try {
            when (action) {
                "run", "exec" -> {
                    val cmd = arguments.string("command") ?: return ToolResult.error("missing \"command\"")
                    blocked(cmd)?.let { return ToolResult.error(it) }
                    ToolResult(linux.run(cmd, ((arguments.int("timeout") ?: 120).coerceIn(5, 300)) * 1000L))
                }
                "start", "background" -> {
                    val cmd = arguments.string("command") ?: return ToolResult.error("missing \"command\"")
                    blocked(cmd)?.let { return ToolResult.error(it) }
                    val id = linux.start(cmd)
                    ToolResult("Started job $id. Check it with {\"action\": \"job\", \"id\": \"$id\"}.")
                }
                "job", "log", "status" -> ToolResult(linux.job(arguments.string("id") ?: return ToolResult.error("missing \"id\"")))
                "stop", "kill" -> ToolResult(linux.stop(arguments.string("id") ?: return ToolResult.error("missing \"id\"")))
                "jobs", "list" -> ToolResult(linux.jobList())
                "write" -> ToolResult(
                    linux.write(
                        arguments.string("path") ?: return ToolResult.error("missing \"path\""),
                        arguments.string("content") ?: return ToolResult.error("missing \"content\"")
                    )
                )
                "read", "cat" -> ToolResult("UNTRUSTED file content:\n" + linux.read(arguments.string("path") ?: return ToolResult.error("missing \"path\"")))
                else -> ToolResult.error("unknown action \"$action\"")
            }
        } catch (e: Exception) {
            ToolResult.error(e.message ?: "linux failed")
        }
    }

    private fun blocked(cmd: String): String? =
        if (Regex(":\\(\\)\\s*\\{").containsMatchIn(cmd)) "that looks like a fork bomb, so TruAgent won't run it." else null
}

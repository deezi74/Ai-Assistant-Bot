package dev.pocketagent.core.tools

import dev.pocketagent.core.agent.AgentMode
import dev.pocketagent.core.agent.Tool
import dev.pocketagent.core.agent.ToolResult
import dev.pocketagent.core.agent.string
import dev.pocketagent.core.code.JsSandbox
import dev.pocketagent.core.code.VirtualComputer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

private val bothModes = setOf(AgentMode.WIT, AgentMode.RESEARCH)

/**
 * Gives the agent its own computer on the phone. [onActivity] receives every
 * command and its output so the Computer screen can show the agent working.
 */
class ComputerTool(
    private val computer: VirtualComputer,
    private val onActivity: (command: String, output: String) -> Unit = { _, _ -> }
) : Tool {
    override val name = "computer"
    override val description =
        "Your own computer that runs on this phone (private sandbox, no internet). Use it to build and run code projects. " +
            "Shell commands: ls, cd, pwd, tree, cat, head, mkdir, rm -r, mv, cp, echo, grep, find, and `run file.js` to execute JavaScript (ES6, console.log, fs.read/write, require). " +
            "To create or overwrite a file pass write_path and content instead of a command. Check results by running the program."
    override val argumentsSignature = """{"command"?: string, "write_path"?: string, "content"?: string}"""
    override val modes = bothModes

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val path = arguments.string("write_path") ?: arguments.string("path")
        val content = arguments.string("content")
        val command = arguments.string("command") ?: arguments.string("cmd")
        val outputs = mutableListOf<String>()
        if (path != null && content != null) {
            val out = computer.writeFile(path, content)
            onActivity("write $path", out)
            outputs += out
        }
        if (command != null) {
            val out = computer.execute(command).let { if (it == VirtualComputer.CLEAR) "" else it }
            onActivity(command, out)
            outputs += "${computer.prompt} $command\n${out.ifEmpty { "(done)" }}"
        }
        if (outputs.isEmpty()) return@withContext ToolResult.error("pass \"command\", or \"write_path\" with \"content\"")
        val text = outputs.joinToString("\n")
        ToolResult(text.take(MAX_RESULT), isError = text.startsWith("error:") || text.contains("\nerror:"))
    }

    private companion object {
        const val MAX_RESULT = 6_000
    }
}

/** Runs a JavaScript snippet in the sandbox for quick calculations and data work. */
class RunCodeTool(
    private val sandbox: JsSandbox,
    private val onActivity: (command: String, output: String) -> Unit = { _, _ -> }
) : Tool {
    override val name = "run_code"
    override val description =
        "Run a JavaScript (ES6) snippet on the phone and get its console output and final value. Good for maths, dates, text and data processing. No internet."
    override val argumentsSignature = """{"code": string}"""
    override val modes = bothModes

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val code = arguments.string("code") ?: arguments.string("input") ?: return@withContext ToolResult.error("missing \"code\"")
        val result = sandbox.run(code)
        val text = result.describe()
        onActivity("run_code", text)
        ToolResult(text.take(6_000), isError = !result.ok)
    }
}

package dev.pocketagent.agent

import dev.pocketagent.core.code.JsSandbox
import dev.pocketagent.core.code.VirtualComputer
import dev.pocketagent.data.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

enum class ComputerActor { YOU, AGENT }

data class ComputerEntry(val id: Long, val actor: ComputerActor, val prompt: String, val command: String, val output: String)

/**
 * The assistant's computer on this phone, shared by the agent's tools and the
 * Computer and Code screens, with a transcript of what both of them ran.
 */
class ComputerSession(workspace: Workspace) {
    val root: File = workspace.computerDir
    val sandbox = JsSandbox(root)
    val computer = VirtualComputer(root, sandbox)

    private val ids = AtomicLong(1)
    private val _transcript = MutableStateFlow<List<ComputerEntry>>(emptyList())
    val transcript: StateFlow<List<ComputerEntry>> = _transcript.asStateFlow()

    /** Bumps whenever files may have changed so file browsers refresh. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    init {
        ensureWelcomeProject()
    }

    /** Commands the user types on the Computer screen. */
    suspend fun run(command: String): String = withContext(Dispatchers.IO) {
        val prompt = computer.prompt
        val out = computer.execute(command)
        if (out == VirtualComputer.CLEAR) {
            _transcript.value = emptyList()
        } else {
            record(ComputerActor.YOU, prompt, command, out)
        }
        _revision.update { it + 1 }
        out
    }

    /** Called by the agent's tools after each command. */
    fun recordAgent(command: String, output: String) {
        record(ComputerActor.AGENT, computer.prompt, command, output)
        _revision.update { it + 1 }
    }

    private fun record(actor: ComputerActor, prompt: String, command: String, output: String) {
        _transcript.update { (it + ComputerEntry(ids.getAndIncrement(), actor, prompt, command, output)).takeLast(MAX_ENTRIES) }
    }

    fun clearTranscript() {
        _transcript.value = emptyList()
    }

    // region Files for the Code screen

    fun projects(): List<File> = root.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() }.orEmpty()

    fun files(project: File): List<File> = project.walkTopDown()
        .filter { it.isFile }
        .sortedBy { it.relativeTo(project).path.lowercase() }
        .toList()

    suspend fun createProject(name: String): File = withContext(Dispatchers.IO) {
        val slug = name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "").replace(' ', '-').ifEmpty { "project" }
        var dir = File(root, slug)
        var i = 2
        while (dir.exists()) dir = File(root, "$slug-${i++}")
        dir.mkdirs()
        File(dir, "main.js").writeText("// ${name.trim()}\nconsole.log('Hello from ${dir.name}!');\n")
        _revision.update { it + 1 }
        dir
    }

    suspend fun writeFile(file: File, content: String) = withContext(Dispatchers.IO) {
        guard(file)
        file.parentFile?.mkdirs()
        file.writeText(content)
        _revision.update { it + 1 }
    }

    suspend fun readFile(file: File): String = withContext(Dispatchers.IO) {
        guard(file)
        if (file.isFile) file.readText() else ""
    }

    suspend fun delete(file: File) = withContext(Dispatchers.IO) {
        guard(file)
        if (file.canonicalPath != root.canonicalPath) file.deleteRecursively()
        _revision.update { it + 1 }
    }

    /** Runs a project file and records it in the transcript as the user's own command. */
    suspend fun runFile(file: File): String = withContext(Dispatchers.IO) {
        guard(file)
        val rel = "/" + file.relativeTo(root).path.replace(File.separatorChar, '/')
        val out = computer.execute("run $rel")
        record(ComputerActor.YOU, computer.prompt, "run $rel", out)
        _revision.update { it + 1 }
        out
    }

    private fun guard(file: File) {
        val rootPath = root.canonicalPath
        val path = file.canonicalPath
        require(path == rootPath || path.startsWith(rootPath + File.separator)) { "outside the computer" }
    }

    // endregion

    private fun ensureWelcomeProject() {
        if (root.listFiles()?.isNotEmpty() == true) return
        val dir = File(root, "welcome").apply { mkdirs() }
        File(dir, "main.js").writeText(
            """
            // Programs on this computer are JavaScript and run right on your phone.
            const { greet } = require('./greet');
            console.log(greet(args[0] || 'friend'));

            const days = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
            console.log('Today is', days[new Date().getDay()]);

            fs.write('visits.txt', String(Number(fs.exists('visits.txt') ? fs.read('visits.txt') : 0) + 1));
            console.log('Runs so far:', fs.read('visits.txt'));
            """.trimIndent() + "\n"
        )
        File(dir, "greet.js").writeText("module.exports = {\n  greet: (name) => `Hi ${'$'}{name}, this program ran on your phone.`\n};\n")
        File(dir, "README.md").writeText(
            "# Welcome\n\nTry `run welcome` in the Computer terminal, or ask the assistant to build something:\n" +
                "\"Use your computer to make a tip calculator\".\n"
        )
    }

    private companion object {
        const val MAX_ENTRIES = 400
    }
}

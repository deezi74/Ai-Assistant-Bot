package dev.pocketagent.core.code

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The assistant's own computer: a small Unix-style shell over a private folder on
 * the phone. Files live in [root]; programs are JavaScript run by [JsSandbox].
 * Nothing here can reach other apps, the network or the rest of the file system.
 */
class VirtualComputer(
    val root: File,
    private val sandbox: JsSandbox = JsSandbox(root),
    private val hostName: String = "truagent"
) {
    init {
        root.mkdirs()
    }

    var cwd: File = root.canonicalFile
        private set

    private val history = ArrayDeque<String>()

    val prompt: String get() = "$hostName:${display(cwd)}$"

    /** Path relative to the computer's root, shown as `/` for the root itself. */
    fun display(file: File): String {
        val rel = file.canonicalPath.removePrefix(root.canonicalPath).replace(File.separatorChar, '/')
        return rel.ifEmpty { "/" }
    }

    /** Runs one command line and returns what it printed. `clear` returns [CLEAR]. */
    @Synchronized
    fun execute(commandLine: String): String {
        val line = commandLine.trim()
        if (line.isEmpty()) return ""
        history.addLast(line)
        while (history.size > MAX_HISTORY) history.removeFirst()
        return try {
            // `a && b` runs b only if a did not report an error.
            val parts = splitAnd(line)
            val outputs = mutableListOf<String>()
            for (part in parts) {
                val result = single(part)
                if (result.isNotEmpty()) outputs += result
                if (result.startsWith("error:") || result.contains("\nerror:")) break
            }
            outputs.joinToString("\n")
        } catch (e: IllegalArgumentException) {
            "error: ${e.message}"
        } catch (e: java.io.IOException) {
            "error: ${e.message}"
        }
    }

    /** Creates or replaces a file, making parent folders as needed. */
    @Synchronized
    fun writeFile(path: String, content: String): String {
        if (content.length > JsSandbox.MAX_FILE_BYTES) return "error: content is larger than ${JsSandbox.MAX_FILE_BYTES / 1_000_000} MB"
        val f = sandbox.resolve(cwd, path)
        if (f.isDirectory) return "error: $path is a folder"
        f.parentFile?.mkdirs()
        val existed = f.exists()
        f.writeText(content)
        return "${if (existed) "updated" else "created"} ${display(f)} (${content.lines().size} lines, ${content.length} chars)"
    }

    @Synchronized
    fun readFile(path: String): String? {
        val f = runCatching { sandbox.resolve(cwd, path) }.getOrNull() ?: return null
        return if (f.isFile) f.readText() else null
    }

    fun resolve(path: String): File = sandbox.resolve(cwd, path)

    private fun single(line: String): String {
        val tokens = tokenize(line)
        if (tokens.isEmpty()) return ""
        val cmd = tokens[0]
        val args = tokens.drop(1)
        return when (cmd) {
            "help", "?" -> HELP
            "pwd" -> display(cwd)
            "cd" -> {
                val target = resolve(args.firstOrNull() ?: "/")
                if (!target.isDirectory) return "error: no such folder: ${args.firstOrNull()}"
                cwd = target
                ""
            }
            "ls", "dir" -> ls(args)
            "tree" -> tree(resolve(args.firstOrNull() ?: "."))
            "cat", "type" -> args.ifEmpty { return "error: usage: cat <file>" }.joinToString("\n") { p ->
                val f = resolve(p)
                when {
                    !f.exists() -> "error: no such file: $p"
                    f.isDirectory -> "error: $p is a folder"
                    f.length() > MAX_CAT_BYTES -> f.readText().take(MAX_CAT_BYTES.toInt()) + "\n[truncated, file is ${f.length()} bytes]"
                    else -> f.readText().trimEnd()
                }
            }
            "head", "tail" -> {
                val n = args.indexOf("-n").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toIntOrNull() } ?: 10
                val path = args.lastOrNull { it != "-n" && it.toIntOrNull() == null } ?: return "error: usage: $cmd [-n N] <file>"
                val f = resolve(path)
                if (!f.isFile) return "error: no such file: $path"
                val lines = f.readLines()
                (if (cmd == "head") lines.take(n) else lines.takeLast(n)).joinToString("\n")
            }
            "wc" -> args.ifEmpty { return "error: usage: wc <file>" }.joinToString("\n") { p ->
                val f = resolve(p)
                if (!f.isFile) "error: no such file: $p" else {
                    val text = f.readText()
                    "${text.lines().size} lines  ${text.split(Regex("\\s+")).count { it.isNotEmpty() }} words  ${text.length} chars  $p"
                }
            }
            "mkdir" -> args.filter { it != "-p" }.ifEmpty { return "error: usage: mkdir <folder>" }.joinToString("\n") { p ->
                val f = resolve(p)
                if (f.mkdirs() || f.isDirectory) "" else "error: could not create $p"
            }.trim()
            "touch" -> args.ifEmpty { return "error: usage: touch <file>" }.joinToString("\n") { p ->
                val f = resolve(p)
                f.parentFile?.mkdirs()
                if (f.exists()) f.setLastModified(System.currentTimeMillis()) else f.createNewFile()
                ""
            }.trim()
            "rm", "del" -> {
                val recursive = args.any { it == "-r" || it == "-rf" || it == "-fr" }
                args.filterNot { it.startsWith("-") }.ifEmpty { return "error: usage: rm [-r] <path>" }.joinToString("\n") { p ->
                    val f = resolve(p)
                    when {
                        f.canonicalPath == root.canonicalPath -> "error: refusing to delete the whole computer"
                        !f.exists() -> "error: no such file: $p"
                        f.isDirectory && !recursive -> "error: $p is a folder (use rm -r)"
                        else -> {
                            if (cwd.canonicalPath.startsWith(f.canonicalPath)) cwd = f.parentFile.canonicalFile
                            f.deleteRecursively()
                            ""
                        }
                    }
                }.trim()
            }
            "mv", "cp" -> {
                if (args.size != 2) return "error: usage: $cmd <from> <to>"
                val from = resolve(args[0])
                var to = resolve(args[1])
                if (!from.exists()) return "error: no such file: ${args[0]}"
                if (to.isDirectory) to = File(to, from.name)
                if (to.canonicalPath.startsWith(from.canonicalPath + File.separator)) return "error: cannot move a folder into itself"
                to.parentFile?.mkdirs()
                if (cmd == "cp") from.copyRecursively(to, overwrite = true) else if (!from.renameTo(to)) {
                    from.copyRecursively(to, overwrite = true)
                    from.deleteRecursively()
                }
                ""
            }
            "echo" -> echo(args)
            "grep" -> grep(args)
            "find" -> find(args)
            "run", "node", "js" -> run(args)
            "eval" -> sandbox.run(args.joinToString(" "), "eval.js", cwd).describe()
            "date" -> SimpleDateFormat("EEE MMM d HH:mm:ss yyyy", Locale.US).format(Date())
            "whoami" -> hostName
            "uname" -> "TruAgent sandbox (JavaScript ES6 via Rhino, runs on this phone)"
            "du", "df" -> "${formatSize(root.walkTopDown().filter { it.isFile }.sumOf { it.length() })} used in ${root.walkTopDown().count { it.isFile }} files"
            "history" -> history.mapIndexed { i, h -> "${i + 1}  $h" }.joinToString("\n")
            "clear", "cls" -> CLEAR
            else -> "error: unknown command: $cmd (try help)"
        }
    }

    private fun ls(args: List<String>): String {
        val long = args.any { it.startsWith("-") && 'l' in it }
        val dir = resolve(args.firstOrNull { !it.startsWith("-") } ?: ".")
        if (!dir.exists()) return "error: no such file or folder"
        if (dir.isFile) return dir.name
        val entries = dir.listFiles().orEmpty().sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        if (entries.isEmpty()) return "(empty)"
        return if (long) {
            val fmt = SimpleDateFormat("MMM d HH:mm", Locale.US)
            entries.joinToString("\n") { f ->
                val size = if (f.isDirectory) "-" else formatSize(f.length())
                "%-8s %-12s %s".format(size, fmt.format(Date(f.lastModified())), if (f.isDirectory) f.name + "/" else f.name)
            }
        } else {
            entries.joinToString("  ") { if (it.isDirectory) it.name + "/" else it.name }
        }
    }

    private fun tree(dir: File): String {
        if (!dir.isDirectory) return "error: not a folder"
        val out = StringBuilder(display(dir)).append('\n')
        var count = 0
        fun walk(d: File, prefix: String) {
            val kids = d.listFiles().orEmpty().sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            kids.forEachIndexed { i, f ->
                if (count >= MAX_TREE) return
                count++
                val last = i == kids.lastIndex
                out.append(prefix).append(if (last) "└── " else "├── ").append(f.name).append(if (f.isDirectory) "/" else "").append('\n')
                if (f.isDirectory) walk(f, prefix + if (last) "    " else "│   ")
            }
        }
        walk(dir, "")
        if (count >= MAX_TREE) out.append("… (more files not shown)\n")
        return out.toString().trimEnd()
    }

    private fun echo(args: List<String>): String {
        val redirect = args.indexOfFirst { it == ">" || it == ">>" }
        if (redirect < 0) return args.joinToString(" ")
        val target = args.getOrNull(redirect + 1) ?: return "error: missing file after ${args[redirect]}"
        val text = args.take(redirect).joinToString(" ").replace("\\n", "\n") + "\n"
        val f = resolve(target)
        f.parentFile?.mkdirs()
        if (args[redirect] == ">>") f.appendText(text) else f.writeText(text)
        return ""
    }

    private fun grep(args: List<String>): String {
        val ignoreCase = "-i" in args
        val rest = args.filterNot { it.startsWith("-") }
        val pattern = rest.firstOrNull() ?: return "error: usage: grep [-i] <text> [path]"
        val start = resolve(rest.getOrNull(1) ?: ".")
        val files = if (start.isFile) sequenceOf(start) else start.walkTopDown().filter { it.isFile && it.length() < MAX_CAT_BYTES }
        val hits = mutableListOf<String>()
        for (f in files) {
            f.readLines().forEachIndexed { i, l ->
                if (l.contains(pattern, ignoreCase) && hits.size < MAX_GREP) hits += "${display(f)}:${i + 1}: ${l.trim().take(200)}"
            }
        }
        return hits.joinToString("\n").ifEmpty { "(no matches)" }
    }

    private fun find(args: List<String>): String {
        val nameIdx = args.indexOf("-name")
        val (path, pattern) = when {
            nameIdx >= 0 -> (args.take(nameIdx).firstOrNull() ?: ".") to args.getOrNull(nameIdx + 1)
            args.size >= 2 -> args[0] to args[1]
            args.size == 1 && resolve(args[0]).isDirectory -> args[0] to null
            else -> "." to args.firstOrNull()
        }
        val start = resolve(path)
        val regex = pattern?.let { p ->
            Regex(p.split('*').joinToString(".*") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
        }
        return start.walkTopDown()
            .filter { it != start && (regex == null || regex.containsMatchIn(it.name)) }
            .take(MAX_GREP)
            .joinToString("\n") { display(it) + if (it.isDirectory) "/" else "" }
            .ifEmpty { "(nothing found)" }
    }

    private fun run(args: List<String>): String {
        if (args.isEmpty()) return "error: usage: run <file.js> [args]"
        if (args[0] == "-e") return sandbox.run(args.drop(1).joinToString(" "), "eval.js", cwd).describe()
        var f = resolve(args[0])
        if (!f.exists() && !args[0].endsWith(".js")) f = resolve(args[0] + ".js")
        if (f.isDirectory) f = File(f, "main.js").takeIf { it.isFile } ?: File(f, "index.js")
        if (!f.isFile) return "error: no such program: ${args[0]}"
        val result = sandbox.run(f.readText(), f.name, f.parentFile ?: cwd, args.drop(1))
        return result.describe() + if (result.millis > 500) "\n(${result.millis} ms)" else ""
    }

    companion object {
        const val CLEAR = "\u000c"
        private const val MAX_HISTORY = 200
        private const val MAX_TREE = 300
        private const val MAX_GREP = 200
        private const val MAX_CAT_BYTES = 200_000L

        val HELP = """
            Files    ls [-l] [path]   cd <folder>   pwd   tree   cat <file>   head/tail [-n N] <file>   wc <file>
                     mkdir <folder>   touch <file>   rm [-r] <path>   mv <a> <b>   cp <a> <b>
                     echo text > file   echo text >> file   grep [-i] <text> [path]   find <pattern>
            Programs run <file.js> [args]   run <folder>  (runs main.js or index.js)   eval <js>   run -e <js>
            Other    date   whoami   uname   du   history   clear   help
            Chain commands with &&. Programs are JavaScript (ES6) with console.log, fs.read/write/list/exists/remove/mkdir, require('./file') and args.
        """.trimIndent()

        fun formatSize(bytes: Long): String = when {
            bytes >= 1_000_000 -> "%.1fM".format(Locale.US, bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.1fK".format(Locale.US, bytes / 1_000.0)
            else -> "${bytes}B"
        }

        fun splitAnd(line: String): List<String> {
            val parts = mutableListOf<String>()
            val cur = StringBuilder()
            var quote: Char? = null
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    quote != null -> {
                        if (c == quote) quote = null
                        cur.append(c)
                    }
                    c == '"' || c == '\'' -> {
                        quote = c
                        cur.append(c)
                    }
                    c == '&' && line.getOrNull(i + 1) == '&' -> {
                        parts += cur.toString().trim()
                        cur.clear()
                        i++
                    }
                    else -> cur.append(c)
                }
                i++
            }
            parts += cur.toString().trim()
            return parts.filter { it.isNotEmpty() }
        }

        /** Splits on whitespace, honouring single and double quotes and `>`/`>>` redirects. */
        fun tokenize(line: String): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var quote: Char? = null
            var started = false
            var i = 0
            fun flush() {
                if (started) out += cur.toString()
                cur.clear()
                started = false
            }
            while (i < line.length) {
                val c = line[i]
                when {
                    quote != null -> if (c == quote) quote = null else cur.append(c)
                    c == '"' || c == '\'' -> {
                        quote = c
                        started = true
                    }
                    c.isWhitespace() -> flush()
                    c == '>' -> {
                        flush()
                        if (line.getOrNull(i + 1) == '>') {
                            out += ">>"
                            i++
                        } else {
                            out += ">"
                        }
                    }
                    else -> {
                        cur.append(c)
                        started = true
                    }
                }
                i++
            }
            flush()
            return out
        }
    }
}

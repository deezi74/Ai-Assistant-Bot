package dev.pocketagent.agent

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.pocketagent.core.agent.AgentMode
import dev.pocketagent.core.agent.Tool
import dev.pocketagent.core.agent.ToolResult
import dev.pocketagent.core.agent.string
import dev.pocketagent.data.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Lists and reads text files inside folders the user mounted in
 * Settings ▸ Mount external folders. Read-only; nothing outside those trees is reachable.
 */
class SharedFolderTool(private val context: Context, private val workspace: Workspace) : Tool {
    override val name = "shared_files"
    override val description = "List or read text files in folders the user has shared with you. Use action \"list\" with an optional path, then \"read\" with a file path."
    override val argumentsSignature = """{"action": "list"|"read", "path"?: string (e.g. "Notes/todo.md")}"""
    override val modes = setOf(AgentMode.WIT, AgentMode.RESEARCH)

    override fun isAvailable(): Boolean = workspace.mounts().isNotEmpty()

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val roots = workspace.mounts().mapNotNull { DocumentFile.fromTreeUri(context, Uri.parse(it)) }.filter { it.canRead() }
        if (roots.isEmpty()) return@withContext ToolResult.error("no shared folders are mounted")
        val action = arguments.string("action") ?: "list"
        val path = arguments.string("path").orEmpty().trim('/', ' ')

        if (path.isEmpty()) {
            if (action == "read") return@withContext ToolResult.error("give a file path to read")
            return@withContext ToolResult(
                "Shared folders:\n" + roots.joinToString("\n") { root ->
                    "- ${root.name}/ (" + (root.listFiles().size) + " entries)"
                }
            )
        }

        val target = resolve(roots, path) ?: return@withContext ToolResult.error("\"$path\" not found in shared folders")
        when {
            target.isDirectory -> {
                val entries = target.listFiles().sortedBy { it.name?.lowercase() }.take(MAX_ENTRIES)
                ToolResult(
                    "$path/\n" + entries.joinToString("\n") { f ->
                        if (f.isDirectory) "- ${f.name}/" else "- ${f.name} (${f.length()} bytes)"
                    }
                )
            }
            action == "list" -> ToolResult("$path (${target.length()} bytes, ${target.type ?: "unknown type"})")
            else -> {
                val type = target.type.orEmpty()
                if (!type.startsWith("text/") && type !in TEXT_TYPES && !looksLikeText(target.name.orEmpty())) {
                    return@withContext ToolResult.error("$path is not a text file ($type)")
                }
                val text = context.contentResolver.openInputStream(target.uri)?.use { input ->
                    val bytes = input.readNBytesCompat(MAX_READ_BYTES)
                    String(bytes, Charsets.UTF_8)
                } ?: return@withContext ToolResult.error("could not open $path")
                val truncated = target.length() > MAX_READ_BYTES
                ToolResult(text + if (truncated) "\n[truncated at ${MAX_READ_BYTES / 1024} KB]" else "")
            }
        }
    }

    private fun resolve(roots: List<DocumentFile>, path: String): DocumentFile? {
        val parts = path.split('/').filter { it.isNotEmpty() }
        val root = roots.firstOrNull { it.name == parts.first() }
        val (start, rest) = if (root != null) root to parts.drop(1) else roots.first() to parts
        var current: DocumentFile = start
        for (part in rest) {
            current = current.findFile(part) ?: return null
        }
        return current
    }

    private fun looksLikeText(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf("md", "txt", "csv", "json", "xml", "html", "htm", "log", "yaml", "yml", "kt", "java", "py", "js", "ts", "sh", "ini", "toml")

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_ENTRIES = 200
        private const val MAX_READ_BYTES = 48 * 1024
        private val TEXT_TYPES = setOf("application/json", "application/xml", "application/x-yaml", "application/csv")
    }
}

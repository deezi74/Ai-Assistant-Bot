package dev.pocketagent.core.tools

import dev.pocketagent.core.agent.AgentMode
import dev.pocketagent.core.agent.Tool
import dev.pocketagent.core.agent.ToolResult
import dev.pocketagent.core.agent.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * Builds Android apps for the user in the cloud: creates a private repo in
 * their GitHub with a working Kotlin/Compose template and an Actions workflow,
 * pushes the agent's code, and reports the build result and APK link.
 *
 * It only ever writes to repos it created itself ([owned]), so it can't touch
 * the user's other repositories.
 */
class AppBuilderTool(
    private val client: GitHubClient,
    private val owned: () -> Set<String>,
    private val remember: (String) -> Unit,
    private val enabled: () -> Boolean
) : Tool {
    override val name = "app_builder"
    override val description =
        "Build a real Android app (APK) for the user with GitHub Actions. create: new private repo with a working Kotlin + Jetpack Compose " +
            "template (package from app_id, main screen in app/src/main/java/<package path>/MainActivity.kt). write: commit files " +
            "(full contents) and start a build. status: latest build result, with compiler errors if it failed. apk: download link. " +
            "files: list the project's files. Workflow: create, write your code, check status until it finishes, fix errors and write again, then give the apk link."
    override val argumentsSignature =
        """{"action": "create"|"write"|"status"|"apk"|"files", "repo"?: "owner/name", "name"?: string, "app_name"?: string, "app_id"?: "com.example.app", """ +
            """"files"?: [{"path": string, "content": string}], "message"?: string}"""
    override val modes = setOf(AgentMode.WIT, AgentMode.RESEARCH)
    override fun isAvailable(): Boolean = enabled() && client.hasToken()

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val action = arguments.string("action")?.lowercase(Locale.US) ?: return ToolResult.error("missing \"action\"")
        return try {
            when (action) {
                "create", "new" -> create(arguments)
                "write", "push", "commit" -> {
                    val repo = ownedRepo(arguments)
                    val files = parseFiles(arguments["files"]) ?: return ToolResult.error("\"files\" must be a list of {path, content}")
                    ToolResult(commit(repo, files, arguments.string("message") ?: "Update app") + "\nBuild started. Check with action \"status\" in about 3 minutes.")
                }
                "status", "build" -> ToolResult(status(repoArg(arguments)))
                "apk", "download" -> ToolResult(apk(repoArg(arguments)))
                "files", "list" -> ToolResult(files(repoArg(arguments)))
                else -> ToolResult.error("unknown action \"$action\"")
            }
        } catch (e: GitHubException) {
            ToolResult.error(e.message ?: "GitHub error")
        } catch (e: Exception) {
            ToolResult.error("app builder: ${e.message}")
        }
    }

    private fun repoArg(a: JsonObject): String {
        val r = a.string("repo")?.trim()?.removePrefix("https://github.com/")?.trimEnd('/') ?: owned().singleOrNull()
            ?: throw GitHubException("missing \"repo\". Apps you built: ${owned().joinToString().ifBlank { "none yet" }}")
        if (!REPO.matches(r)) throw GitHubException("\"repo\" must look like owner/name")
        return r
    }

    private fun ownedRepo(a: JsonObject): String {
        val r = repoArg(a)
        if (owned().none { it.equals(r, ignoreCase = true) }) {
            throw GitHubException("$r wasn't created by the app builder, so it won't write to it. Create a new app instead.")
        }
        return r
    }

    private suspend fun create(a: JsonObject): ToolResult {
        val appName = a.string("app_name")?.trim()?.take(40)?.ifBlank { null } ?: a.string("name")?.trim() ?: return ToolResult.error("missing \"app_name\"")
        val repoName = (a.string("name") ?: appName).lowercase(Locale.US).replace(Regex("[^a-z0-9._-]+"), "-").trim('-').take(80).ifBlank { "my-app" }
        val appId = a.string("app_id")?.trim()?.lowercase(Locale.US)
            ?: ("com.truagent." + repoName.replace(Regex("[^a-z0-9]"), "").ifBlank { "app" }.let { if (it.first().isDigit()) "a$it" else it })
        if (!APP_ID.matches(appId)) return ToolResult.error("\"app_id\" must look like com.example.app (lowercase letters, digits, dots)")
        val created = client.post(
            "/user/repos",
            buildJsonObject {
                put("name", JsonPrimitive(repoName))
                put("description", JsonPrimitive("$appName, built with TruAgent"))
                put("private", JsonPrimitive(true))
                put("auto_init", JsonPrimitive(true))
            }
        ).jsonObject
        val full = created.str("full_name") ?: throw GitHubException("GitHub didn't return the new repo name")
        remember(full)
        val template = AndroidAppTemplate.files(appName, appId)
        val result = commit(full, template, "Start $appName from the TruAgent template")
        return ToolResult(
            "Created private repo $full for \"$appName\" (package $appId). $result\n" +
                "Main screen: ${AndroidAppTemplate.mainActivityPath(appId)}. The template already builds; replace or add files with action \"write\".\n" +
                "Template files: ${template.keys.joinToString()}"
        )
    }

    /** Commits all [files] in one commit through the Git data API; pushing to main starts the workflow. */
    private suspend fun commit(repo: String, files: Map<String, String>, message: String): String {
        if (files.isEmpty()) throw GitHubException("no files to write")
        if (files.size > 60) throw GitHubException("too many files in one write (max 60)")
        val branch = client.get("/repos/$repo").jsonObject.str("default_branch") ?: "main"
        // A repo created a moment ago can take a second before its first commit is readable.
        var ref: JsonObject? = null
        for (attempt in 1..5) {
            ref = try {
                client.get("/repos/$repo/git/ref/heads/$branch").jsonObject
            } catch (e: GitHubException) {
                if (attempt == 5) throw e
                kotlinx.coroutines.delay(1500L * attempt)
                null
            }
            if (ref != null) break
        }
        ref!!
        val head = ref.obj("object")?.str("sha") ?: throw GitHubException("couldn't read $branch")
        val baseTree = client.get("/repos/$repo/git/commits/$head").jsonObject.obj("tree")?.str("sha") ?: throw GitHubException("couldn't read the tree")
        val tree = client.post(
            "/repos/$repo/git/trees",
            buildJsonObject {
                put("base_tree", JsonPrimitive(baseTree))
                put(
                    "tree",
                    buildJsonArray {
                        files.forEach { (path, content) ->
                            add(
                                buildJsonObject {
                                    put("path", JsonPrimitive(path))
                                    put("mode", JsonPrimitive("100644"))
                                    put("type", JsonPrimitive("blob"))
                                    put("content", JsonPrimitive(content))
                                }
                            )
                        }
                    }
                )
            }
        ).jsonObject.str("sha") ?: throw GitHubException("couldn't create the tree")
        val commit = client.post(
            "/repos/$repo/git/commits",
            buildJsonObject {
                put("message", JsonPrimitive(message.take(200)))
                put("tree", JsonPrimitive(tree))
                put("parents", buildJsonArray { add(JsonPrimitive(head)) })
            }
        ).jsonObject.str("sha") ?: throw GitHubException("couldn't create the commit")
        client.patch("/repos/$repo/git/refs/heads/$branch", buildJsonObject { put("sha", JsonPrimitive(commit)) })
        return "Committed ${files.size} file(s) to $repo (${commit.take(7)})."
    }

    private fun parseFiles(e: kotlinx.serialization.json.JsonElement?): Map<String, String>? {
        val arr = e as? JsonArray ?: return null
        val out = LinkedHashMap<String, String>()
        for (item in arr) {
            val o = item as? JsonObject ?: return null
            val path = normalize(o.str("path") ?: return null) ?: throw GitHubException("bad path \"${o.str("path")}\"")
            out[path] = o.str("content") ?: return null
        }
        return out
    }

    private fun normalize(path: String): String? {
        val parts = ArrayDeque<String>()
        for (seg in path.trim().split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull() ?: return null
                else -> parts.addLast(seg)
            }
        }
        if (parts.isEmpty() || parts.first() == ".git") return null
        return parts.joinToString("/")
    }

    private suspend fun status(repo: String): String {
        val runs = client.get("/repos/$repo/actions/runs", mapOf("per_page" to "1")).jsonObject["workflow_runs"]?.jsonArray.orEmpty()
        val run = runs.firstOrNull()?.jsonObject ?: return "No builds yet for $repo. Builds start a few seconds after a write."
        val st = run.str("status").orEmpty()
        val conclusion = run.str("conclusion")
        val head = "Build #${run.num("run_number")} of $repo (${run.str("head_sha")?.take(7)}): "
        if (st != "completed") return head + "$st. A build takes about 3 to 6 minutes; check again soon."
        if (conclusion == "success") return head + "succeeded. " + apk(repo)
        val jobs = client.get("/repos/$repo/actions/runs/${run.num("id") ?: run.str("id")}/jobs").jsonObject["jobs"]?.jsonArray.orEmpty()
        val failed = jobs.map { it.jsonObject }.firstOrNull { it.str("conclusion") == "failure" }
        val step = failed?.get("steps")?.jsonArray?.map { it.jsonObject }?.firstOrNull { it.str("conclusion") == "failure" }?.str("name")
        val errors = failed?.let { j -> runCatching { errorsFrom(client.getText("/repos/$repo/actions/jobs/${j.num("id") ?: j.str("id")}/logs")) }.getOrNull() }
        return head + "failed${step?.let { " at \"$it\"" } ?: ""}.\n" + (errors ?: "No log available: ${run.str("html_url")}") +
            "\nFix the files and write them again."
    }

    private suspend fun apk(repo: String): String {
        val rel = client.get("/repos/$repo/releases", mapOf("per_page" to "1")).jsonArray.firstOrNull()?.jsonObject
            ?: return "No APK released yet for $repo."
        val asset = rel["assets"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it.str("name")?.endsWith(".apk") == true }
            ?: return "Release ${rel.str("tag_name")} has no APK yet."
        return "APK: ${asset.str("browser_download_url")} (release ${rel.str("tag_name")}, page ${rel.str("html_url")}). " +
            "The repo is private, so the user must be signed in to GitHub in the browser to download it."
    }

    private suspend fun files(repo: String): String {
        val branch = client.get("/repos/$repo").jsonObject.str("default_branch") ?: "main"
        val tree = client.get("/repos/$repo/git/trees/$branch", mapOf("recursive" to "1")).jsonObject["tree"]?.jsonArray.orEmpty()
        return "Files in $repo:\n" + tree.map { it.jsonObject }.filter { it.str("type") == "blob" }.joinToString("\n") { "- " + it.str("path") }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.num(key: String): Long? = (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    companion object {
        private val REPO = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
        private val APP_ID = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")

        /** Pulls compiler and Gradle error lines out of an Actions log so the model can fix them. */
        fun errorsFrom(log: String): String {
            val lines = log.lines().map { it.replace(Regex("^\\d{4}-\\d\\d-\\d\\dT[\\d:.]+Z "), "") }
            val hits = lines.withIndex().filter { (_, l) ->
                l.startsWith("e: ") || l.contains("error:", ignoreCase = true) || l.contains("FAILURE:") ||
                    l.contains("What went wrong") || l.contains("Unresolved reference") || l.contains("AAPT: error")
            }
            if (hits.isEmpty()) return lines.takeLast(40).joinToString("\n").takeLast(3000)
            val picked = LinkedHashSet<Int>()
            hits.take(25).forEach { (i, _) -> (i..minOf(i + 2, lines.lastIndex)).forEach { picked += it } }
            return "Errors:\n" + picked.joinToString("\n") { lines[it] }.take(4000)
        }
    }
}

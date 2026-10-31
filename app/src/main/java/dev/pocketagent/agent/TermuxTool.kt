package dev.pocketagent.agent

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.core.content.ContextCompat
import dev.pocketagent.core.agent.AgentMode
import dev.pocketagent.core.agent.Tool
import dev.pocketagent.core.agent.ToolResult
import dev.pocketagent.core.agent.int
import dev.pocketagent.core.agent.string
import dev.pocketagent.core.tools.AndroidAppTemplate
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Runs commands in the user's own Termux through its RUN_COMMAND API. Termux
 * only accepts them after the user sets allow-external-apps=true and grants
 * TruAgent the "Run commands in Termux" permission, and the user also has to
 * turn this on in TruAgent. Long jobs (an Android build) run in the background
 * with a log file the agent can check.
 */
class TermuxBridge(private val context: Context) {

    data class Result(val stdout: String, val stderr: String, val exitCode: Int?, val error: String?)

    fun isInstalled(): Boolean = runCatching { context.packageManager.getPackageInfo(TERMUX, 0) }.isSuccess

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Runs `bash -lc script` in Termux's home and waits up to [timeoutMs] for the result. */
    suspend fun run(script: String, timeoutMs: Long = 120_000): Result {
        if (!isInstalled()) return Result("", "", null, "Termux isn't installed. Install it from F-Droid.")
        if (!hasPermission()) return Result("", "", null, "TruAgent isn't allowed to run commands in Termux yet. Grant it in Settings ▸ Connectors ▸ Termux.")
        val action = "dev.pocketagent.TERMUX_RESULT." + UUID.randomUUID()
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        runCatching { context.unregisterReceiver(this) }
                        val b: Bundle? = intent.getBundleExtra("result")
                        val r = Result(
                            stdout = b?.getString("stdout").orEmpty(),
                            stderr = b?.getString("stderr").orEmpty(),
                            exitCode = b?.takeIf { it.containsKey("exitCode") }?.getInt("exitCode"),
                            error = b?.getString("errmsg")?.takeIf { it.isNotBlank() && (b.getInt("err", -1) != -1) }
                        )
                        if (cont.isActive) cont.resume(r)
                    }
                }
                ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
                val flags = PendingIntent.FLAG_ONE_SHOT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val reply = PendingIntent.getBroadcast(context, action.hashCode(), Intent(action).setPackage(context.packageName), flags)
                val intent = Intent("com.termux.RUN_COMMAND")
                    .setComponent(ComponentName(TERMUX, "com.termux.app.RunCommandService"))
                    .putExtra("com.termux.RUN_COMMAND_PATH", "$PREFIX/bin/bash")
                    .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", script))
                    .putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
                    .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                    .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "TruAgent")
                    .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", reply)
                try {
                    try {
                        context.startService(intent)
                    } catch (e: IllegalStateException) {
                        context.startForegroundService(intent)
                    }
                } catch (e: Exception) {
                    runCatching { context.unregisterReceiver(receiver) }
                    if (cont.isActive) cont.resume(Result("", "", null, "Termux refused the command: ${e.message}. Check allow-external-apps in ~/.termux/termux.properties."))
                }
            }
        } ?: Result("", "", null, "no answer from Termux within ${timeoutMs / 1000}s. For long work use action \"start\" (runs in the background).")
    }

    companion object {
        const val TERMUX = "com.termux"
        const val PERMISSION = "com.termux.permission.RUN_COMMAND"
        const val PREFIX = "/data/data/com.termux/files/usr"
        const val HOME = "/data/data/com.termux/files/home"
    }
}

class TermuxTool(private val termux: TermuxBridge, private val enabled: () -> Boolean) : Tool {
    override val name = "termux"
    override val description =
        "Run shell commands in the user's Termux (Linux on the phone). run: a short command (2 min max). start: a long command in the background, " +
            "returns a job id; job: show a job's log and exit code. write: write a text file. new_app: create an Android project in ~/apps/<name> from a " +
            "working Compose template. setup_android: install JDK, Gradle and the Android SDK once (about 1 GB, 10 to 20 minutes, runs as a job). " +
            "build: build ~/apps/<name> into an APK as a job and copy it to Downloads. Explain what a command does before running anything that changes files."
    override val argumentsSignature =
        """{"action": "run"|"start"|"job"|"write"|"new_app"|"setup_android"|"build", "command"?: string, "id"?: string, "path"?: string, "content"?: string, "name"?: string, "app_name"?: string, "app_id"?: string, "timeout"?: int}"""
    override val modes = setOf(AgentMode.WIT, AgentMode.RESEARCH)
    override fun isAvailable(): Boolean = enabled() && termux.isInstalled()

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val action = arguments.string("action")?.lowercase(Locale.US) ?: "run"
        return when (action) {
            "run", "exec" -> {
                val cmd = arguments.string("command") ?: return ToolResult.error("missing \"command\"")
                blocked(cmd)?.let { return ToolResult.error(it) }
                report(termux.run(cmd, ((arguments.int("timeout") ?: 60).coerceIn(5, 120)) * 1000L))
            }
            "start", "background" -> {
                val cmd = arguments.string("command") ?: return ToolResult.error("missing \"command\"")
                blocked(cmd)?.let { return ToolResult.error(it) }
                startJob(cmd)
            }
            "job", "log", "status" -> {
                val id = arguments.string("id")?.takeIf { JOB.matches(it) } ?: return ToolResult.error("missing or bad job \"id\"")
                report(
                    termux.run(
                        "cd ~/.truagent/jobs && if [ -f $id.exit ]; then echo \"[finished, exit code \$(cat $id.exit)]\"; else echo '[still running]'; fi; " +
                            "echo '--- last lines ---'; tail -n 60 $id.log 2>/dev/null"
                    )
                )
            }
            "write" -> {
                val path = arguments.string("path") ?: return ToolResult.error("missing \"path\"")
                val content = arguments.string("content") ?: return ToolResult.error("missing \"content\"")
                val clean = homePath(path) ?: return ToolResult.error("path must stay inside the Termux home folder")
                report(termux.run(writeScript(mapOf(clean to content))), ok = "Wrote ~/$clean")
            }
            "new_app" -> {
                val appName = arguments.string("app_name") ?: arguments.string("name") ?: return ToolResult.error("missing \"app_name\"")
                val dir = safeName(arguments.string("name") ?: appName)
                val appId = arguments.string("app_id") ?: "com.truagent.${dir.replace("-", "").ifBlank { "app" }}"
                val files = AndroidAppTemplate.files(appName, appId).filterKeys { !it.startsWith(".github/") }
                    .mapKeys { "apps/$dir/${it.key}" } + mapOf("apps/$dir/local.properties" to "sdk.dir=${TermuxBridge.HOME}/android-sdk\n")
                report(
                    termux.run(writeScript(files)),
                    ok = "Created ~/apps/$dir (package $appId). Main screen: ~/apps/$dir/${AndroidAppTemplate.mainActivityPath(appId)}. " +
                        "Build it with action \"build\" and name \"$dir\" (run setup_android once first)."
                )
            }
            "setup_android" -> startJob(SETUP_SCRIPT)
            "build" -> {
                val dir = safeName(arguments.string("name") ?: return ToolResult.error("missing \"name\" (folder in ~/apps)"))
                startJob(
                    "cd ~/apps/$dir && export ANDROID_HOME=~/android-sdk && gradle --no-daemon assembleDebug && " +
                        "APK=\$(ls app/build/outputs/apk/debug/*.apk | head -n1) && mkdir -p ~/storage/downloads 2>/dev/null; " +
                        "if [ -d ~/storage/downloads ]; then cp \"\$APK\" ~/storage/downloads/$dir.apk && echo \"APK copied to Downloads/$dir.apk\"; " +
                        "else echo \"APK at \$PWD/\$APK (run termux-setup-storage to copy builds to Downloads)\"; fi"
                )
            }
            else -> ToolResult.error("unknown action \"$action\"")
        }
    }

    private suspend fun startJob(cmd: String): ToolResult {
        val id = "job" + System.currentTimeMillis().toString(36)
        val b64 = Base64.encodeToString(cmd.toByteArray(), Base64.NO_WRAP)
        val script = "mkdir -p ~/.truagent/jobs && cd ~/.truagent/jobs && echo '$b64' | base64 -d > $id.sh && " +
            "(command -v termux-wake-lock >/dev/null && termux-wake-lock; nohup bash -l $id.sh > $id.log 2>&1; echo \$? > $id.exit) > /dev/null 2>&1 & echo started"
        val r = termux.run(script, 30_000)
        if (r.error != null) return ToolResult.error(r.error)
        return ToolResult("Started background job $id. Check it with action \"job\" and id \"$id\" (long builds take 5 to 30 minutes on a phone).")
    }

    private fun report(r: TermuxBridge.Result, ok: String? = null): ToolResult {
        if (r.error != null) return ToolResult.error(r.error)
        val out = buildString {
            if (ok != null && r.exitCode == 0) append(ok).append('\n')
            append("exit code ").append(r.exitCode ?: "?").append('\n')
            if (r.stdout.isNotBlank()) append(r.stdout.takeLast(MAX)).append('\n')
            if (r.stderr.isNotBlank()) append("stderr:\n").append(r.stderr.takeLast(MAX / 2))
        }.trim()
        return if (r.exitCode == 0 || r.exitCode == null) ToolResult(out) else ToolResult.error(out)
    }

    private fun writeScript(files: Map<String, String>): String = files.entries.joinToString(" && ") { (path, content) ->
        val b64 = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)
        "mkdir -p \"\$(dirname ~/'$path')\" && echo '$b64' | base64 -d > ~/'$path'"
    }

    private fun homePath(path: String): String? {
        val parts = ArrayDeque<String>()
        for (seg in path.trim().removePrefix("~").removePrefix(TermuxBridge.HOME).split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull() ?: return null
                else -> if (seg.contains('\'')) return null else parts.addLast(seg)
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("/")
    }

    private fun safeName(s: String) = s.lowercase(Locale.US).replace(Regex("[^a-z0-9_-]+"), "-").trim('-').take(60).ifBlank { "app" }

    /** Refuses a few commands that wipe the phone's storage or the Termux install. */
    private fun blocked(cmd: String): String? {
        val c = cmd.replace(Regex("\\s+"), " ")
        val bad = listOf(
            Regex("rm -[a-zA-Z]*[rf][a-zA-Z]* +(/|~|\\\$HOME|/data|/sdcard|/storage|~/storage)/?( |$|;|&)"),
            Regex("\\bmkfs"), Regex("\\bdd\\b.*of=/dev/"), Regex(":\\(\\)\\s*\\{")
        )
        return if (bad.any { it.containsMatchIn(c) }) "that command could wipe your files, so TruAgent won't run it. Run it yourself in Termux if you really mean it." else null
    }

    companion object {
        private val JOB = Regex("^job[a-z0-9]{4,16}$")
        private const val MAX = 6000

        /** One-time Android build setup inside Termux (aarch64): JDK, Gradle, aapt2 and the SDK platform. */
        val SETUP_SCRIPT = """
            set -e
            yes | pkg update
            pkg install -y openjdk-17 gradle aapt2 wget unzip git
            SDK=${'$'}HOME/android-sdk
            mkdir -p ${'$'}SDK/cmdline-tools
            if [ ! -x ${'$'}SDK/cmdline-tools/latest/bin/sdkmanager ]; then
              wget -q -O ${'$'}PREFIX/tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
              rm -rf ${'$'}SDK/cmdline-tools/latest ${'$'}SDK/cmdline-tools/cmdline-tools
              unzip -q ${'$'}PREFIX/tmp/clt.zip -d ${'$'}SDK/cmdline-tools
              mv ${'$'}SDK/cmdline-tools/cmdline-tools ${'$'}SDK/cmdline-tools/latest
            fi
            yes | ${'$'}SDK/cmdline-tools/latest/bin/sdkmanager --sdk_root=${'$'}SDK "platforms;android-35" "build-tools;35.0.0" > /dev/null
            grep -q ANDROID_HOME ~/.bashrc 2>/dev/null || echo "export ANDROID_HOME=${'$'}SDK" >> ~/.bashrc
            mkdir -p ~/.gradle
            grep -q aapt2FromMavenOverride ~/.gradle/gradle.properties 2>/dev/null || echo "android.aapt2FromMavenOverride=${'$'}PREFIX/bin/aapt2" >> ~/.gradle/gradle.properties
            java -version 2>&1 | head -n1
            gradle --version | grep Gradle
            echo "Android build setup finished."
        """.trimIndent()
    }
}

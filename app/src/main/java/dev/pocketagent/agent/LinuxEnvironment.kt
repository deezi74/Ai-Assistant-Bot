package dev.pocketagent.agent

import android.content.Context
import android.system.Os
import dev.pocketagent.core.linux.TarExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * TruAgent's built-in Linux: an Alpine Linux root filesystem inside the app's private
 * storage, run through PRoot (bundled as libproot.so). No root and no Termux needed.
 * Commands see Alpine's own files only; the phone's storage is not mounted.
 */
class LinuxEnvironment(private val context: Context) {
    sealed interface State {
        data object NotInstalled : State
        data class Installing(val step: String) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val base = File(context.filesDir, "linux")
    val rootfs = File(base, "rootfs")
    private val tmp = File(base, "tmp")
    private val marker = File(base, ".installed")
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private val proot = File(nativeDir, "libproot.so")
    private val jobs = ConcurrentHashMap<String, Job>()

    private class Job(val process: Process, val log: File, val command: String, val started: Long)

    private val _state = MutableStateFlow<State>(if (marker.isFile) State.Ready else State.NotInstalled)
    val state: StateFlow<State> = _state.asStateFlow()

    val engineBundled: Boolean get() = proot.isFile
    val installed: Boolean get() = marker.isFile && rootfs.isDirectory

    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    /** Downloads Alpine Linux (about 4 MB) and sets it up with a few basics. */
    suspend fun install(): String = withContext(Dispatchers.IO) {
        if (!engineBundled) throw IOException("This build has no Linux engine for this phone (arm64 only).")
        try {
            _state.value = State.Installing("Finding the latest Alpine Linux…")
            val index = get("$ALPINE/latest-releases.yaml").decodeToString()
            val file = Regex("file:\\s*(alpine-minirootfs-[0-9.]+-aarch64\\.tar\\.gz)").find(index)?.groupValues?.get(1)
                ?: throw IOException("Couldn't find the Alpine download")
            _state.value = State.Installing("Downloading $file…")
            val archive = File(context.cacheDir, file)
            download("$ALPINE/$file", archive)
            _state.value = State.Installing("Unpacking…")
            if (base.exists()) base.deleteRecursively()
            rootfs.mkdirs()
            val result = archive.inputStream().use { input ->
                TarExtractor(
                    symlink = { target, link -> Os.symlink(target, link.absolutePath) },
                    chmod = { f, mode -> runCatching { Os.chmod(f.absolutePath, mode and 0b111_111_111) } }
                ).extractGz(input, rootfs)
            }
            archive.delete()
            File(rootfs, "etc/resolv.conf").apply { delete() }.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
            File(rootfs, "root").mkdirs()
            File(rootfs, "etc/profile.d").mkdirs()
            File(rootfs, "etc/profile.d/truagent.sh").writeText("export PS1='truagent:\\w# '\nexport LANG=C.UTF-8\n")
            tmp.mkdirs()
            marker.writeText("${result.files} files")
            _state.value = State.Installing("Installing basics (bash, curl, git, python)…")
            val setup = run("apk update && apk add --no-progress bash curl git python3 py3-pip nodejs npm nano", 600_000)
            _state.value = State.Ready
            "Alpine Linux is installed (${result.files} files).\n" + setup.takeLast(1500)
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: "install failed")
            throw e
        }
    }

    fun uninstall() {
        jobs.values.forEach { it.process.destroy() }
        jobs.clear()
        base.deleteRecursively()
        _state.value = State.NotInstalled
    }

    private fun command(script: String): ProcessBuilder {
        val pb = ProcessBuilder(
            proot.absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${tmp.absolutePath}:/tmp",
            "-w", "/root",
            "/bin/sh", "-lc", script
        )
        val env = pb.environment()
        env.clear()
        env["PROOT_LOADER"] = File(nativeDir, "libproot-loader.so").absolutePath
        File(nativeDir, "libproot-loader32.so").takeIf { it.isFile }?.let { env["PROOT_LOADER_32"] = it.absolutePath }
        env["PROOT_TMP_DIR"] = tmp.absolutePath
        env["LD_LIBRARY_PATH"] = nativeDir.absolutePath
        env["HOME"] = "/root"
        env["USER"] = "root"
        env["TERM"] = "xterm-256color"
        env["LANG"] = "C.UTF-8"
        env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        tmp.mkdirs()
        return pb.redirectErrorStream(true)
    }

    /** Runs a shell command and returns its output (last 12k characters) and exit code. */
    suspend fun run(script: String, timeoutMs: Long = 120_000): String = withContext(Dispatchers.IO) {
        check(installed || _state.value is State.Installing) { "Linux isn't installed yet" }
        val process = command(script).start()
        val out = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().use { r ->
                val buf = CharArray(4096)
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    synchronized(out) {
                        out.append(buf, 0, n)
                        if (out.length > 48_000) out.delete(0, out.length - 24_000)
                    }
                }
            }
        }.apply { start() }
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(2_000)
        val text = synchronized(out) { out.toString() }.takeLast(12_000)
        if (!finished) "$text\n[stopped after ${timeoutMs / 1000} s; use start for long jobs]" else "$text\n[exit ${process.exitValue()}]"
    }

    /** Starts a long-running command in the background and returns its job id. */
    fun start(script: String): String {
        check(installed) { "Linux isn't installed yet" }
        val id = (jobs.size + 1).toString() + "-" + (System.currentTimeMillis() % 100000)
        val log = File(base, "jobs/$id.log").apply { parentFile?.mkdirs() }
        val process = command(script).redirectOutput(log).start()
        jobs[id] = Job(process, log, script, System.currentTimeMillis())
        return id
    }

    fun job(id: String): String {
        val job = jobs[id] ?: return "No job $id. Running jobs: ${jobs.keys.joinToString().ifEmpty { "none" }}"
        val status = if (job.process.isAlive) "running" else "finished (exit ${job.process.exitValue()})"
        return "Job $id: $status, started ${(System.currentTimeMillis() - job.started) / 1000} s ago\n$ ${job.command}\n" +
            (if (job.log.isFile) job.log.readText().takeLast(8_000) else "")
    }

    fun stop(id: String): String {
        val job = jobs.remove(id) ?: return "No job $id"
        job.process.destroy()
        return "Stopped job $id"
    }

    fun jobList(): String = jobs.entries.joinToString("\n") { (id, j) -> "$id: ${if (j.process.isAlive) "running" else "done"} · ${j.command.take(80)}" }
        .ifEmpty { "No background jobs." }

    /** Writes a file inside Linux (path relative to /root unless absolute). */
    fun write(path: String, content: String): String {
        val target = resolve(path)
        target.parentFile?.mkdirs()
        target.writeText(content)
        return "Wrote ${content.length} characters to ${display(path)}"
    }

    fun read(path: String): String {
        val f = resolve(path)
        if (!f.isFile) return "No file at ${display(path)}"
        return f.readText().take(20_000)
    }

    private fun display(path: String) = if (path.startsWith("/")) path else "/root/$path"

    private fun resolve(path: String): File {
        val inside = if (path.startsWith("/")) path else "/root/$path"
        val f = File(rootfs, inside.trimStart('/'))
        if (!f.toPath().normalize().startsWith(rootfs.toPath())) throw IOException("path outside Linux: $path")
        return f
    }

    fun diskUsage(): Long = rootfs.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private fun get(url: String): ByteArray = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) throw IOException("Download failed (HTTP ${r.code})")
        r.body?.bytes() ?: throw IOException("Empty download")
    }

    private fun download(url: String, to: File) = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) throw IOException("Download failed (HTTP ${r.code})")
        r.body?.byteStream()?.use { input -> to.outputStream().use { input.copyTo(it) } } ?: throw IOException("Empty download")
    }

    companion object {
        private const val ALPINE = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64"
    }
}

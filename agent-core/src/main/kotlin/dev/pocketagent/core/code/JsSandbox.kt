package dev.pocketagent.core.code

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.io.File

data class RunResult(
    val output: String,
    /** The value of the last expression, formatted, or null when it was undefined. */
    val value: String?,
    val error: String?,
    val millis: Long
) {
    val ok: Boolean get() = error == null

    /** One block of text for the terminal and for the agent. */
    fun describe(): String = buildString {
        if (output.isNotEmpty()) append(output.trimEnd()).append('\n')
        if (value != null) append("=> ").append(value).append('\n')
        if (error != null) append("error: ").append(error).append('\n')
        if (isEmpty()) append("(no output)\n")
    }.trimEnd()
}

/** Thrown from the instruction observer; extends Error so scripts cannot catch it. */
private class ScriptTimeout(val limitMs: Long) : Error("script stopped after ${limitMs / 1000.0}s")

private class OutputLimit : Error("output limit reached")

/**
 * Runs JavaScript (ES6 subset, Rhino interpreter) entirely on the device.
 *
 * The sandbox has no Java access (every class is shut out), no network and no
 * process control. Its only window to the outside is a small `fs` API confined
 * to [root], plus `console.log`. A wall-clock deadline stops runaway loops.
 */
class JsSandbox(
    private val root: File,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val maxOutputChars: Int = DEFAULT_MAX_OUTPUT
) {
    init {
        root.mkdirs()
    }

    private val rootPath: String get() = root.canonicalPath

    fun run(source: String, sourceName: String = "snippet.js", cwd: File = root, args: List<String> = emptyList()): RunResult {
        val started = System.currentTimeMillis()
        val out = StringBuilder()
        val cx = factory.enterContext()
        try {
            cx.putThreadLocal(DEADLINE_KEY, started + timeoutMs)
            cx.putThreadLocal(LIMIT_KEY, timeoutMs)
            val scope = cx.initSafeStandardObjects()
            installHost(cx, scope, out, cwd)
            ScriptableObject.putProperty(scope, "args", cx.newArray(scope, args.toTypedArray<Any>()))
            cx.evaluateString(scope, BOOTSTRAP, "bootstrap.js", 1, null)
            val result = cx.evaluateString(scope, source, sourceName, 1, null)
            val value = if (result == null || result is Undefined) null else format(cx, scope, result)
            return RunResult(out.toString(), value, null, System.currentTimeMillis() - started)
        } catch (e: ScriptTimeout) {
            return RunResult(out.toString(), null, e.message, System.currentTimeMillis() - started)
        } catch (e: OutputLimit) {
            out.append("\n[output truncated at $maxOutputChars characters]")
            return RunResult(out.toString(), null, null, System.currentTimeMillis() - started)
        } catch (e: RhinoException) {
            val where = if (e.lineNumber() > 0) " (${e.sourceName()}:${e.lineNumber()})" else ""
            return RunResult(out.toString(), null, e.details() + where, System.currentTimeMillis() - started)
        } catch (e: StackOverflowError) {
            return RunResult(out.toString(), null, "too much recursion", System.currentTimeMillis() - started)
        } catch (e: OutOfMemoryError) {
            return RunResult(out.toString(), null, "out of memory", System.currentTimeMillis() - started)
        } catch (e: Exception) {
            return RunResult(out.toString(), null, e.message ?: e.javaClass.simpleName, System.currentTimeMillis() - started)
        } finally {
            Context.exit()
        }
    }

    private fun format(cx: Context, scope: Scriptable, value: Any): String {
        val fmt = ScriptableObject.getProperty(scope, "__fmt") as? Function ?: return Context.toString(value)
        return Context.toString(fmt.call(cx, scope, scope, arrayOf(value)))
    }

    /**
     * Resolves [path] against [cwd] (or [root] for absolute paths) like a chroot:
     * `..` stops at [root], and anything that still lands outside (a symlink) is refused.
     */
    fun resolve(cwd: File, path: String): File {
        val clean = path.trim().replace('\\', '/').ifEmpty { "." }
        val segments = ArrayList<String>()
        if (!clean.startsWith("/")) {
            val rel = cwd.canonicalPath.removePrefix(rootPath).replace(File.separatorChar, '/')
            rel.split('/').filterTo(segments) { it.isNotEmpty() }
        }
        for (part in clean.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments += part
            }
        }
        val file = segments.fold(File(rootPath)) { dir, name -> File(dir, name) }.canonicalFile
        if (file.path != rootPath && !file.path.startsWith(rootPath + File.separator)) {
            throw IllegalArgumentException("$path is outside the workspace")
        }
        return file
    }

    private fun installHost(cx: Context, scope: Scriptable, out: StringBuilder, cwd: File) {
        fun path(args: Array<Any?>, i: Int = 0): File {
            val raw = args.getOrNull(i)?.let { Context.toString(it) } ?: throw Context.reportRuntimeError("path required")
            return try {
                resolve(cwd, raw)
            } catch (e: IllegalArgumentException) {
                throw Context.reportRuntimeError(e.message)
            }
        }
        fun text(args: Array<Any?>, i: Int): String = args.getOrNull(i)?.let { Context.toString(it) } ?: ""

        define(scope, "__out", 1) { args ->
            val line = text(args, 0)
            if (out.length + line.length + 1 > maxOutputChars) {
                out.append(line.take((maxOutputChars - out.length).coerceAtLeast(0)))
                throw OutputLimit()
            }
            out.append(line).append('\n')
            Undefined.instance
        }
        define(scope, "__read", 1) { args ->
            val f = path(args)
            if (!f.isFile) throw Context.reportRuntimeError("no such file: ${text(args, 0)}")
            if (f.length() > MAX_FILE_BYTES) throw Context.reportRuntimeError("file is larger than ${MAX_FILE_BYTES / 1_000_000} MB")
            f.readText()
        }
        define(scope, "__write", 2) { args ->
            val f = path(args)
            val content = text(args, 1)
            if (content.length > MAX_FILE_BYTES) throw Context.reportRuntimeError("content is larger than ${MAX_FILE_BYTES / 1_000_000} MB")
            f.parentFile?.mkdirs()
            f.writeText(content)
            content.length.toDouble()
        }
        define(scope, "__append", 2) { args ->
            val f = path(args)
            val content = text(args, 1)
            if (f.length() + content.length > MAX_FILE_BYTES) throw Context.reportRuntimeError("file would exceed ${MAX_FILE_BYTES / 1_000_000} MB")
            f.parentFile?.mkdirs()
            f.appendText(content)
            content.length.toDouble()
        }
        define(scope, "__list", 1) { args ->
            val dir = if (args.isEmpty() || args[0] is Undefined) cwd else path(args)
            if (!dir.isDirectory) throw Context.reportRuntimeError("not a directory: ${text(args, 0)}")
            val names = dir.listFiles().orEmpty().sortedBy { it.name }.map { if (it.isDirectory) it.name + "/" else it.name }
            cx.newArray(scope, names.toTypedArray<Any>())
        }
        define(scope, "__exists", 1) { args -> path(args).exists() }
        define(scope, "__remove", 1) { args ->
            val f = path(args)
            if (f.canonicalPath == rootPath) throw Context.reportRuntimeError("refusing to delete the workspace root")
            f.exists() && f.deleteRecursively()
        }
        define(scope, "__mkdir", 1) { args ->
            val f = path(args)
            f.mkdirs() || f.isDirectory
        }
    }

    private fun define(scope: Scriptable, name: String, arity: Int, body: (Array<Any?>) -> Any?) {
        val fn = object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? = body(args)
            override fun getFunctionName(): String = name
            override fun getArity(): Int = arity
            override fun getLength(): Int = arity
        }
        ScriptableObject.defineProperty(scope, name, fn, ScriptableObject.DONTENUM or ScriptableObject.READONLY)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val DEFAULT_MAX_OUTPUT = 20_000
        const val MAX_FILE_BYTES = 2_000_000L
        private const val DEADLINE_KEY = "truagent.deadline"
        private const val LIMIT_KEY = "truagent.limit"

        private val factory = object : ContextFactory() {
            override fun makeContext(): Context = super.makeContext().apply {
                optimizationLevel = -1 // interpreter: Android cannot load generated bytecode
                languageVersion = Context.VERSION_ES6
                instructionObserverThreshold = 10_000
                maximumInterpreterStackDepth = 2_000
                setClassShutter { false }
            }

            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                val deadline = cx.getThreadLocal(DEADLINE_KEY) as? Long ?: return
                if (System.currentTimeMillis() > deadline) throw ScriptTimeout(cx.getThreadLocal(LIMIT_KEY) as? Long ?: 0L)
            }
        }

        private val BOOTSTRAP = """
            (function (g) {
              function fmt(v) {
                if (typeof v === 'string') return v;
                if (v === undefined) return 'undefined';
                if (typeof v === 'function') return '[Function ' + (v.name || 'anonymous') + ']';
                try { var s = JSON.stringify(v, null, v && typeof v === 'object' && JSON.stringify(v).length > 80 ? 2 : 0); return s === undefined ? String(v) : s; }
                catch (e) { return String(v); }
              }
              function join(a) { var o = []; for (var i = 0; i < a.length; i++) o.push(fmt(a[i])); return o.join(' '); }
              g.__fmt = fmt;
              g.console = {
                log: function () { __out(join(arguments)); },
                info: function () { __out(join(arguments)); },
                debug: function () { __out(join(arguments)); },
                warn: function () { __out('warn: ' + join(arguments)); },
                error: function () { __out('error: ' + join(arguments)); },
                table: function (rows) { __out(fmt(rows)); }
              };
              g.print = g.console.log;
              g.fs = {
                read: __read, write: __write, append: __append, list: __list,
                exists: __exists, remove: __remove, mkdir: __mkdir,
                readJSON: function (p) { return JSON.parse(__read(p)); },
                writeJSON: function (p, v) { return __write(p, JSON.stringify(v, null, 2)); }
              };
              var cache = {};
              g.require = function (p) {
                if (!/\.js${'$'}/.test(p)) p = p + '.js';
                if (cache[p]) return cache[p].exports;
                var m = { exports: {} };
                cache[p] = m;
                var fn = new Function('module', 'exports', 'require', __read(p));
                fn(m, m.exports, g.require);
                return m.exports;
              };
            })(this);
        """.trimIndent()
    }
}

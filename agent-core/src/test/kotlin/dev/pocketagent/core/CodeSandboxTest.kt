package dev.pocketagent.core

import dev.pocketagent.core.code.JsSandbox
import dev.pocketagent.core.code.VirtualComputer
import dev.pocketagent.core.tools.ComputerTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CodeSandboxTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun runsJavaScriptAndCapturesOutput() {
        val sandbox = JsSandbox(tmp.root)
        val r = sandbox.run("const xs = [1,2,3].map(x => x * 2); console.log('sum', xs.reduce((a, b) => a + b, 0)); xs")
        assertNull(r.error)
        assertEquals("sum 12\n", r.output)
        assertEquals("[2,4,6]", r.value)
    }

    @Test
    fun stopsInfiniteLoops() {
        val sandbox = JsSandbox(tmp.root, timeoutMs = 300)
        val r = sandbox.run("try { while (true) {} } catch (e) { 'caught' }")
        assertNotNull(r.error)
        assertTrue(r.error!!, r.error!!.contains("stopped"))
    }

    @Test
    fun hasNoJavaAccess() {
        val sandbox = JsSandbox(tmp.root)
        val r = sandbox.run("typeof java === 'undefined' && typeof Packages === 'undefined'")
        assertEquals("true", r.value)
        val escape = sandbox.run("java.lang.System.exit(1)")
        assertNotNull(escape.error)
    }

    @Test
    fun fileApiIsConfinedToWorkspace() {
        val sandbox = JsSandbox(tmp.root)
        val ok = sandbox.run("fs.write('a/b.txt', 'hi'); fs.read('a/b.txt')")
        assertEquals("hi", ok.value)
        val bad = sandbox.run("fs.read('../../../../../../etc/passwd')")
        assertNotNull(bad.error)
        assertTrue(bad.error!!, bad.error!!.contains("no such file"))
        assertEquals(tmp.root.canonicalPath + "/etc/passwd", sandbox.resolve(tmp.root, "../../etc/passwd").path)
    }

    @Test
    fun requireLoadsModules() {
        val sandbox = JsSandbox(tmp.root)
        sandbox.run("fs.write('lib.js', 'module.exports = { double: function (n) { return n * 2; } };')")
        val r = sandbox.run("require('./lib').double(21)")
        assertEquals("42", r.value)
    }

    @Test
    fun nestedRequireResolvesRelativeToEachModule() {
        val pc = VirtualComputer(tmp.root)
        pc.writeFile("app/main.js", "const u = require('./lib/util'); console.log(u.shout('hi'), require('./data.json').n)")
        pc.writeFile("app/lib/util.js", "const fmt = require('./fmt'); module.exports = { shout: s => fmt.bang(s.toUpperCase()) };")
        pc.writeFile("app/lib/fmt/index.js", "exports.bang = s => s + '!';")
        pc.writeFile("app/data.json", "{\"n\": 7}")
        assertEquals("HI! 7", pc.execute("run app/main.js"))
        assertEquals("HI! 7", pc.execute("cd app && run main.js"))
        assertTrue(pc.execute("eval require('../../../etc/passwd')").contains("error"))
    }

    @Test
    fun shellBuildsAndRunsAProject() {
        val pc = VirtualComputer(tmp.root)
        assertEquals("", pc.execute("mkdir -p hello && cd hello"))
        assertEquals("/hello", pc.execute("pwd"))
        pc.writeFile("main.js", "console.log('hello ' + (args[0] || 'world'))")
        assertEquals("hello phone", pc.execute("run main.js phone"))
        pc.execute("echo first line > notes.txt")
        pc.execute("echo second >> notes.txt")
        assertEquals("first line\nsecond", pc.execute("cat notes.txt"))
        assertTrue(pc.execute("grep second").contains("notes.txt:2"))
        assertTrue(pc.execute("tree /").contains("main.js"))
        assertTrue(pc.execute("cd ../..").isEmpty())
        assertEquals("/", pc.execute("pwd"))
        assertTrue(pc.execute("cat /../secret").startsWith("error:"))
        assertTrue(pc.execute("rm -r /").startsWith("error:"))
        assertTrue(pc.execute("find main").contains("/hello/main.js"))
    }

    @Test
    fun computerToolWritesAndRuns() = runTest {
        val pc = VirtualComputer(tmp.root)
        val seen = mutableListOf<String>()
        val tool = ComputerTool(pc) { cmd, _ -> seen += cmd }
        val r = tool.execute(
            buildJsonObject {
                put("write_path", JsonPrimitive("calc/main.js"))
                put("content", JsonPrimitive("console.log(6 * 7)"))
                put("command", JsonPrimitive("run calc/main.js"))
            }
        )
        assertFalse(r.output, r.isError)
        assertTrue(r.output, r.output.contains("42"))
        assertEquals(listOf("write calc/main.js", "run calc/main.js"), seen)
    }
}

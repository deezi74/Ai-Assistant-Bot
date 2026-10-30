package dev.pocketagent.core

import dev.pocketagent.core.llm.GgufMetadataReader
import dev.pocketagent.core.llm.LlamaBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Exercises the real JNI bridge against a host build of libagentnative. Runs only when
 * -Dpocketagent.nativeLib=/path/libagentnative.so and -Dpocketagent.testModel=/path/model.gguf are set.
 */
class GgufAndNativeTest {
    private val libPath = System.getProperty("pocketagent.nativeLib").orEmpty()
    private val modelPath = System.getProperty("pocketagent.testModel").orEmpty()

    @Test
    fun ggufHeaderIsReadAndEstimated() {
        assumeTrue("set -Dpocketagent.testModel", modelPath.isNotBlank())
        val meta = GgufMetadataReader.read(File(modelPath))
        assertTrue(meta.blockCount > 0)
        assertTrue(meta.embeddingLength > 0)
        assertTrue(meta.kvCacheBytes(1024) > 0)
        assertTrue(meta.estimatedRuntimeBytes(1024, 512) > meta.fileSizeBytes)
        println("GGUF: arch=${meta.architecture} layers=${meta.blockCount} embd=${meta.embeddingLength} heads=${meta.headCount}/${meta.headCountKv} ctx=${meta.contextLength} kv@1024=${meta.kvCacheBytes(1024)}")
    }

    @Test
    fun nativeBridgeGeneratesAndReusesPrefix() {
        assumeTrue("set -Dpocketagent.nativeLib and -Dpocketagent.testModel", libPath.isNotBlank() && modelPath.isNotBlank())
        LlamaBridge.ensureLoaded(libPath)
        val model = LlamaBridge.nativeLoadModel(modelPath, 0, true, false)
        assertTrue("model handle", model != 0L)
        println("info=" + LlamaBridge.nativeModelInfo(model))
        val session = LlamaBridge.nativeNewSession(model, 512, 64, 2)
        assertTrue("session handle", session != 0L)
        assertEquals(512, LlamaBridge.nativeContextSize(session))

        val prompt = LlamaBridge.nativeApplyChatTemplate(
            model, arrayOf("system", "user"), arrayOf("You are terse.", "Say hi 👋"), true
        )
        assertNotNull(prompt)
        println("prompt=${prompt!!.replace("\n", "\\n")}")

        fun generate(p: String): String {
            val rc = LlamaBridge.nativeStartCompletion(session, p, 0.0f, 1.0f, 0, 0.0f, 1.0f, 64, 1, 24)
            assertTrue("start rc=$rc", rc > 0)
            val out = StringBuilder()
            var n = 0
            while (n < 24) {
                val bytes = LlamaBridge.nativeNextToken(session) ?: break
                out.append(String(bytes, Charsets.UTF_8))
                n++
            }
            return out.toString()
        }

        val first = generate(prompt)
        val again = generate(prompt)
        println("gen1=[$first] gen2=[$again]")
        assertEquals("greedy decoding must be deterministic across prefix reuse", first, again)

        LlamaBridge.nativeAbort(session)
        LlamaBridge.nativeResetSession(session)
        LlamaBridge.nativeFreeSession(session)
        LlamaBridge.nativeFreeModel(model)
    }
}

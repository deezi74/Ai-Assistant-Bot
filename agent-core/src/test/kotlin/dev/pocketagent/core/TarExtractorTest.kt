package dev.pocketagent.core

import dev.pocketagent.core.linux.TarExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class TarExtractorTest {
    private fun header(name: String, size: Int, type: Char, link: String = "", mode: Int = 0b111_101_101): ByteArray {
        val h = ByteArray(512)
        fun put(off: Int, s: String) = s.toByteArray().copyInto(h, off)
        put(0, name)
        put(100, "%07o".format(mode))
        put(108, "0000000"); put(116, "0000000")
        put(124, "%011o".format(size))
        put(136, "00000000000")
        h[156] = type.code.toByte()
        put(157, link)
        put(257, "ustar"); put(263, "00")
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        val sum = h.sumOf { it.toInt() and 0xff }
        put(148, "%06o".format(sum)); h[154] = 0
        return h
    }

    private fun entry(out: ByteArrayOutputStream, name: String, type: Char, data: ByteArray = ByteArray(0), link: String = "") {
        out.write(header(name, data.size, type, link))
        out.write(data)
        val pad = (512 - data.size % 512) % 512
        out.write(ByteArray(pad))
    }

    @Test
    fun extractsFilesDirsLinksAndLongNames() {
        val tar = ByteArrayOutputStream()
        entry(tar, "./bin/", '5')
        entry(tar, "./bin/busybox", '0', "#!busybox".toByteArray())
        entry(tar, "./bin/sh", '2', link = "/bin/busybox")
        entry(tar, "./bin/ash", '1', link = "./bin/busybox")
        val long = "etc/" + "x".repeat(120) + ".conf"
        entry(tar, "././@LongLink", 'L', (long + "\u0000").toByteArray())
        entry(tar, "ignored", '0', "long ok".toByteArray())
        tar.write(ByteArray(1024))

        val dest = Files.createTempDirectory("rootfs").toFile()
        val r = TarExtractor(symlink = { target, link -> Files.createSymbolicLink(link.toPath(), File(target).toPath()) })
            .extract(ByteArrayInputStream(tar.toByteArray()), dest)
        assertEquals("#!busybox", File(dest, "bin/busybox").readText())
        assertTrue(File(dest, "bin/busybox").canExecute())
        assertEquals("/bin/busybox", Files.readSymbolicLink(File(dest, "bin/sh").toPath()).toString())
        assertEquals("#!busybox", File(dest, "bin/ash").readText())
        assertEquals("long ok", File(dest, long).readText())
        assertEquals(1, r.links)
    }

    @Test(expected = java.io.IOException::class)
    fun refusesPathTraversal() {
        val tar = ByteArrayOutputStream()
        entry(tar, "../evil", '0', "x".toByteArray())
        tar.write(ByteArray(1024))
        TarExtractor(symlink = { _, _ -> }).extract(ByteArrayInputStream(tar.toByteArray()), Files.createTempDirectory("r").toFile())
    }
}

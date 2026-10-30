package dev.pocketagent.core.llm

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Reads the key/value header of a GGUF file without loading tensors, so the app
 * can estimate RAM needs before handing the file to llama.cpp.
 */
data class GgufMetadata(
    val version: Int,
    val tensorCount: Long,
    val architecture: String,
    val name: String?,
    val blockCount: Int,
    val embeddingLength: Int,
    val headCount: Int,
    val headCountKv: Int,
    val contextLength: Int,
    val fileType: Int?,
    val chatTemplate: String?,
    val fileSizeBytes: Long,
    val values: Map<String, Any?>
) {
    /** Bytes of K+V cache for [nCtx] tokens with an f16 cache. */
    fun kvCacheBytes(nCtx: Int): Long {
        if (blockCount <= 0 || embeddingLength <= 0) return 0L
        val heads = if (headCount > 0) headCount else 1
        val kvHeads = if (headCountKv > 0) headCountKv else heads
        val headDim = embeddingLength.toLong() / heads
        val kvDim = headDim * kvHeads
        return 2L * blockCount * nCtx.toLong() * kvDim * 2L
    }

    /**
     * Total working-set estimate: mapped weights + KV cache + compute buffers
     * (scratch grows with context and batch; 15% of weights plus 64 MiB covers
     * llama.cpp's CPU graphs in practice).
     */
    fun estimatedRuntimeBytes(nCtx: Int, nBatch: Int): Long {
        val compute = (fileSizeBytes * 0.15).toLong() + 64L * 1024 * 1024 + nBatch.toLong() * embeddingLength * 4L * 8L
        return fileSizeBytes + kvCacheBytes(nCtx) + compute
    }
}

object GgufMetadataReader {
    private const val GGUF_MAGIC = 0x46554747 // "GGUF" little-endian

    private const val T_UINT8 = 0
    private const val T_INT8 = 1
    private const val T_UINT16 = 2
    private const val T_INT16 = 3
    private const val T_UINT32 = 4
    private const val T_INT32 = 5
    private const val T_FLOAT32 = 6
    private const val T_BOOL = 7
    private const val T_STRING = 8
    private const val T_ARRAY = 9
    private const val T_UINT64 = 10
    private const val T_INT64 = 11
    private const val T_FLOAT64 = 12

    /** Arrays longer than this (token lists, merges) are skipped instead of materialised. */
    private const val MAX_ARRAY_KEEP = 64

    fun read(file: File): GgufMetadata {
        if (!file.isFile) throw IOException("Not a file: ${file.absolutePath}")
        return file.inputStream().use { read(it, file.length()) }
    }

    fun read(input: InputStream, fileSize: Long): GgufMetadata {
        val reader = LittleEndianReader(DataInputStream(BufferedInputStream(input, 1 shl 16)))
        val magic = reader.int32()
        if (magic != GGUF_MAGIC) throw IOException("Not a GGUF file (magic=0x${magic.toUInt().toString(16)})")
        val version = reader.int32()
        if (version < 2) throw IOException("Unsupported GGUF version $version")
        val tensorCount = reader.int64()
        val kvCount = reader.int64()
        if (kvCount < 0 || kvCount > 1_000_000) throw IOException("Corrupt GGUF header (kv=$kvCount)")

        val values = LinkedHashMap<String, Any?>()
        for (i in 0 until kvCount) {
            val key = reader.string()
            val type = reader.int32()
            values[key] = readValue(reader, type)
        }

        val arch = values["general.architecture"] as? String ?: "unknown"
        fun intKey(suffix: String): Int = (values["$arch.$suffix"] as? Number)?.toInt() ?: 0

        return GgufMetadata(
            version = version,
            tensorCount = tensorCount,
            architecture = arch,
            name = values["general.name"] as? String,
            blockCount = intKey("block_count"),
            embeddingLength = intKey("embedding_length"),
            headCount = headCountOf(values["$arch.attention.head_count"]),
            headCountKv = headCountOf(values["$arch.attention.head_count_kv"]),
            contextLength = intKey("context_length"),
            fileType = (values["general.file_type"] as? Number)?.toInt(),
            chatTemplate = values["tokenizer.chat_template"] as? String,
            fileSizeBytes = fileSize,
            values = values
        )
    }

    /** Some architectures store per-layer head counts as arrays; take the maximum. */
    private fun headCountOf(raw: Any?): Int = when (raw) {
        is Number -> raw.toInt()
        is List<*> -> raw.filterIsInstance<Number>().maxOfOrNull { it.toInt() } ?: 0
        else -> 0
    }

    private fun readValue(r: LittleEndianReader, type: Int): Any? = when (type) {
        T_UINT8 -> r.uint8()
        T_INT8 -> r.int8()
        T_UINT16 -> r.uint16()
        T_INT16 -> r.int16()
        T_UINT32 -> r.uint32()
        T_INT32 -> r.int32()
        T_FLOAT32 -> r.float32()
        T_BOOL -> r.uint8() != 0
        T_STRING -> r.string()
        T_UINT64, T_INT64 -> r.int64()
        T_FLOAT64 -> r.float64()
        T_ARRAY -> {
            val elementType = r.int32()
            val length = r.int64()
            if (length < 0) throw IOException("Negative array length")
            if (length <= MAX_ARRAY_KEEP) {
                List(length.toInt()) { readValue(r, elementType) }
            } else {
                for (i in 0 until length) skipValue(r, elementType)
                ArraySummary(elementType, length)
            }
        }
        else -> throw IOException("Unknown GGUF value type $type")
    }

    private fun skipValue(r: LittleEndianReader, type: Int) {
        when (type) {
            T_UINT8, T_INT8, T_BOOL -> r.skip(1)
            T_UINT16, T_INT16 -> r.skip(2)
            T_UINT32, T_INT32, T_FLOAT32 -> r.skip(4)
            T_UINT64, T_INT64, T_FLOAT64 -> r.skip(8)
            T_STRING -> r.skip(r.int64())
            T_ARRAY -> {
                val elementType = r.int32()
                val length = r.int64()
                for (i in 0 until length) skipValue(r, elementType)
            }
            else -> throw IOException("Unknown GGUF value type $type")
        }
    }

    data class ArraySummary(val elementType: Int, val length: Long)

    private class LittleEndianReader(private val input: DataInputStream) {
        private val buf = ByteArray(8)

        private fun fill(n: Int) {
            input.readFully(buf, 0, n)
        }

        fun uint8(): Int { fill(1); return buf[0].toInt() and 0xFF }
        fun int8(): Int { fill(1); return buf[0].toInt() }
        fun uint16(): Int { fill(2); return (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8) }
        fun int16(): Int = uint16().toShort().toInt()
        fun int32(): Int {
            fill(4)
            return (buf[0].toInt() and 0xFF) or
                ((buf[1].toInt() and 0xFF) shl 8) or
                ((buf[2].toInt() and 0xFF) shl 16) or
                ((buf[3].toInt() and 0xFF) shl 24)
        }
        fun uint32(): Long = int32().toLong() and 0xFFFFFFFFL
        fun int64(): Long {
            fill(8)
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (buf[i].toLong() and 0xFF)
            return v
        }
        fun float32(): Float = java.lang.Float.intBitsToFloat(int32())
        fun float64(): Double = java.lang.Double.longBitsToDouble(int64())

        fun string(): String {
            val len = int64()
            if (len < 0 || len > 64L * 1024 * 1024) throw IOException("Corrupt GGUF string length $len")
            val bytes = ByteArray(len.toInt())
            input.readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        fun skip(n: Long) {
            var remaining = n
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped <= 0) {
                    if (input.read() < 0) throw EOFException()
                    remaining -= 1
                } else {
                    remaining -= skipped
                }
            }
        }
    }
}

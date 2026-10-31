package dev.pocketagent.core.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

data class HfModelSummary(val id: String, val downloads: Long, val likes: Long)
data class HfFile(val path: String, val sizeBytes: Long)

/** Parsed "owner/repo", "owner/repo:file.gguf" or a full huggingface.co URL. */
data class HfReference(val repo: String, val file: String?, val revision: String = "main") {
    companion object {
        fun parse(input: String): HfReference {
            val s = input.trim().removeSuffix("/")
            val urlPrefix = Regex("^https?://(www\\.)?huggingface\\.co/")
            if (urlPrefix.containsMatchIn(s)) {
                val path = s.replace(urlPrefix, "")
                val parts = path.split('/')
                require(parts.size >= 2) { "Not a model URL: $input" }
                val repo = parts[0] + "/" + parts[1]
                val markerIdx = parts.indexOfFirst { it == "resolve" || it == "blob" }
                return if (markerIdx >= 0 && parts.size > markerIdx + 2) {
                    HfReference(repo, parts.drop(markerIdx + 2).joinToString("/").substringBefore('?'), parts[markerIdx + 1])
                } else {
                    HfReference(repo, null)
                }
            }
            val repoPart = s.substringBefore(':')
            val filePart = s.substringAfter(':', "").ifBlank { null }
            require(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(repoPart)) { "Expected owner/repo, got '$input'" }
            return HfReference(repoPart, filePart)
        }
    }
}

/**
 * Minimal Hugging Face Hub client: model search, GGUF listing and resumable
 * streaming downloads with progress callbacks.
 */
class HuggingFaceClient(
    httpClient: OkHttpClient? = null,
    private val baseUrl: String = "https://huggingface.co"
) {
    private val client = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    fun searchGgufModels(query: String, limit: Int = 20, token: String? = null): List<HfModelSummary> {
        val url = "$baseUrl/api/models".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("filter", "gguf")
            .addQueryParameter("sort", "downloads")
            .addQueryParameter("direction", "-1")
            .addQueryParameter("limit", limit.toString())
            .build()
        val body = get(url.toString(), token)
        val arr = json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: (o["modelId"] as? JsonPrimitive)?.contentOrNull
                ?: return@mapNotNull null
            HfModelSummary(
                id,
                (o["downloads"] as? JsonPrimitive)?.longOrNull ?: 0L,
                (o["likes"] as? JsonPrimitive)?.longOrNull ?: 0L
            )
        }
    }

    fun listGgufFiles(repo: String, revision: String = "main", token: String? = null): List<HfFile> {
        val body = get("$baseUrl/api/models/$repo/tree/$revision?recursive=true", token)
        return parseTree(body)
    }

    internal fun parseTree(body: String): List<HfFile> {
        val arr = json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            if ((o["type"] as? JsonPrimitive)?.contentOrNull != "file") return@mapNotNull null
            val path = (o["path"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (!path.endsWith(".gguf", ignoreCase = true)) return@mapNotNull null
            val lfsSize = ((o["lfs"] as? JsonObject)?.get("size") as? JsonPrimitive)?.longOrNull
            HfFile(path, lfsSize ?: (o["size"] as? JsonPrimitive)?.longOrNull ?: 0L)
        }.sortedBy { it.sizeBytes }
    }

    fun resolveUrl(ref: HfReference, file: String): String =
        "$baseUrl/${ref.repo}/resolve/${ref.revision}/${file.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }}"

    /**
     * Streams [url] into [destination], resuming from a `.part` file when present.
     * [onProgress] receives (downloadedBytes, totalBytes or -1); returning false cancels.
     */
    fun download(
        url: String,
        destination: File,
        token: String? = null,
        onProgress: (Long, Long) -> Boolean
    ): File {
        destination.parentFile?.mkdirs()
        val part = File(destination.parentFile, destination.name + ".part")
        val existing = if (part.exists()) part.length() else 0L
        val builder = Request.Builder().url(url).get().header("User-Agent", "TruAgent/1.0")
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        if (existing > 0) builder.header("Range", "bytes=$existing-")
        client.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 416) {
                // Range not satisfiable: the part file is already complete.
                if (!part.renameTo(destination)) throw IOException("Could not finalise ${destination.name}")
                return destination
            }
            if (!resp.isSuccessful) {
                throw IOException(
                    when (resp.code) {
                        401, 403 -> "HTTP ${resp.code}: this repo is gated or private. Add a Hugging Face token in Settings."
                        404 -> "HTTP 404: file not found at $url"
                        else -> "HTTP ${resp.code} downloading $url"
                    }
                )
            }
            val body = resp.body ?: throw IOException("Empty body")
            val resumed = resp.code == 206 && existing > 0
            val startAt = if (resumed) existing else 0L
            val contentLength = body.contentLength()
            val total = if (contentLength >= 0) startAt + contentLength else -1L
            RandomAccessFile(part, "rw").use { raf ->
                if (!resumed) raf.setLength(0)
                raf.seek(startAt)
                val source = body.byteStream()
                val buffer = ByteArray(1 shl 16)
                var done = startAt
                var lastReport = 0L
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    raf.write(buffer, 0, n)
                    done += n
                    if (done - lastReport >= REPORT_EVERY || done == total) {
                        lastReport = done
                        if (!onProgress(done, total)) throw DownloadCancelledException()
                    }
                }
                onProgress(done, total)
                if (total > 0 && done < total) throw IOException("Connection closed early ($done of $total bytes)")
            }
        }
        if (destination.exists()) destination.delete()
        if (!part.renameTo(destination)) throw IOException("Could not finalise ${destination.name}")
        return destination
    }

    private fun get(url: String, token: String?): String {
        val builder = Request.Builder().url(url).get().header("Accept", "application/json")
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            return resp.body?.string().orEmpty()
        }
    }

    class DownloadCancelledException : IOException("Download cancelled")

    companion object {
        private const val REPORT_EVERY = 512L * 1024
    }
}

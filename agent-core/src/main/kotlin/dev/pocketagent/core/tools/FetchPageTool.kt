package dev.pocketagent.core.tools

import dev.pocketagent.core.agent.AgentMode
import dev.pocketagent.core.agent.Tool
import dev.pocketagent.core.agent.ToolResult
import dev.pocketagent.core.agent.int
import dev.pocketagent.core.agent.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Downloads a page and returns its readable text so the model can go beyond search snippets. */
class FetchPageTool(httpClient: OkHttpClient? = null) : Tool {
    private val client = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override val name = "fetch_page"
    override val description = "Fetch a web page and return its main readable text."
    override val argumentsSignature = """{"url": string, "max_chars"?: int (default 6000)}"""
    override val modes = setOf(AgentMode.WIT, AgentMode.RESEARCH)

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val raw = arguments.string("url") ?: arguments.string("input") ?: return ToolResult.error("missing \"url\"")
        val url = (if (raw.startsWith("http")) raw else "https://$raw").toHttpUrlOrNull()
            ?: return ToolResult.error("invalid url '$raw'")
        val maxChars = (arguments.int("max_chars") ?: 6000).coerceIn(500, 20_000)
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", WebSearchTool.USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
                    .get()
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext ToolResult.error("HTTP ${resp.code} for $url")
                    val contentType = resp.header("Content-Type").orEmpty()
                    val body = resp.body?.string().orEmpty()
                    val text = if (contentType.contains("html") || body.trimStart().startsWith("<")) {
                        extractReadableText(body, url.toString())
                    } else {
                        body
                    }
                    ToolResult(buildString {
                        append("URL: ").append(resp.request.url).append('\n')
                        append(text.take(maxChars))
                        if (text.length > maxChars) append("\n[page truncated at $maxChars characters]")
                    })
                }
            } catch (e: IOException) {
                ToolResult.error("fetch failed: ${e.message}")
            }
        }
    }

    companion object {
        fun extractReadableText(html: String, baseUri: String): String {
            val doc = Jsoup.parse(html, baseUri)
            doc.select("script, style, noscript, svg, nav, footer, header, form, iframe, aside, [aria-hidden=true]").remove()
            val title = doc.title()
            val main = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body() ?: doc
            val blocks = main.select("h1, h2, h3, h4, p, li, pre, blockquote, td")
            val text = if (blocks.isEmpty()) {
                main.text()
            } else {
                blocks.mapNotNull { el ->
                    val t = el.text().trim()
                    when {
                        t.isEmpty() -> null
                        el.tagName().startsWith("h") -> "\n## $t"
                        el.tagName() == "li" -> "- $t"
                        else -> t
                    }
                }.distinct().joinToString("\n")
            }
            return if (title.isNotBlank()) "Title: $title\n$text" else text
        }
    }
}

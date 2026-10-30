package dev.pocketagent.core.tools

import dev.pocketagent.core.agent.AgentMode
import dev.pocketagent.core.agent.Tool
import dev.pocketagent.core.agent.ToolResult
import dev.pocketagent.core.agent.int
import dev.pocketagent.core.agent.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

enum class SearchBackend { DUCKDUCKGO, SEARXNG }

data class SearchSettings(
    val backend: SearchBackend = SearchBackend.DUCKDUCKGO,
    /** Base URL of a SearXNG instance with the JSON format enabled, e.g. https://searx.example.org */
    val searxngUrl: String = "",
    val region: String = "wt-wt",
    val safeSearch: Boolean = true
)

data class SearchHit(val title: String, val url: String, val snippet: String)

/**
 * Live web retrieval without API keys: scrapes DuckDuckGo's HTML endpoint (falling
 * back to the lite endpoint) or queries a SearXNG instance's JSON API.
 */
class WebSearchTool(
    private val settingsProvider: () -> SearchSettings,
    httpClient: OkHttpClient? = null,
    private val duckDuckGoHtmlUrl: String = "https://html.duckduckgo.com/html/",
    private val duckDuckGoLiteUrl: String = "https://lite.duckduckgo.com/lite/"
) : Tool {

    private val client = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    override val name = "web_search"
    override val description = "Search the live web. Returns titles, URLs and snippets of the top results."
    override val argumentsSignature = """{"query": string, "max_results"?: int (1-10, default 6)}"""
    override val modes = setOf(AgentMode.WIT, AgentMode.RESEARCH)

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val query = arguments.string("query") ?: arguments.string("q") ?: arguments.string("input")
            ?: return ToolResult.error("missing \"query\"")
        val max = (arguments.int("max_results") ?: 6).coerceIn(1, 10)
        val hits = try {
            search(query, max)
        } catch (e: IOException) {
            return ToolResult.error("search failed: ${e.message}")
        }
        if (hits.isEmpty()) return ToolResult("No results for \"$query\".")
        return ToolResult(format(query, hits))
    }

    suspend fun search(query: String, max: Int): List<SearchHit> = withContext(Dispatchers.IO) {
        val settings = settingsProvider()
        when (settings.backend) {
            SearchBackend.SEARXNG -> {
                if (settings.searxngUrl.isBlank()) throw IOException("SearXNG URL is not configured")
                searchSearxng(settings, query, max)
            }
            SearchBackend.DUCKDUCKGO -> {
                val html = searchDuckDuckGoHtml(settings, query, max)
                if (html.isNotEmpty()) html else searchDuckDuckGoLite(settings, query, max)
            }
        }
    }

    private fun searchDuckDuckGoHtml(settings: SearchSettings, query: String, max: Int): List<SearchHit> {
        val form = FormBody.Builder()
            .add("q", query)
            .add("kl", settings.region)
            .add("kp", if (settings.safeSearch) "1" else "-2")
            .build()
        val request = Request.Builder()
            .url(duckDuckGoHtmlUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.8")
            .post(form)
            .build()
        val body = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("DuckDuckGo HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        return parseDuckDuckGoHtml(body, max)
    }

    private fun searchDuckDuckGoLite(settings: SearchSettings, query: String, max: Int): List<SearchHit> {
        val form = FormBody.Builder().add("q", query).add("kl", settings.region).build()
        val request = Request.Builder()
            .url(duckDuckGoLiteUrl)
            .header("User-Agent", USER_AGENT)
            .post(form)
            .build()
        val body = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("DuckDuckGo lite HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        return parseDuckDuckGoLite(body, max)
    }

    private fun searchSearxng(settings: SearchSettings, query: String, max: Int): List<SearchHit> {
        val base = (settings.searxngUrl.trimEnd('/') + "/search").toHttpUrlOrNull()
            ?: throw IOException("Invalid SearXNG URL")
        val url = base.newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("format", "json")
            .addQueryParameter("safesearch", if (settings.safeSearch) "1" else "0")
            .build()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        val body = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("SearXNG HTTP ${resp.code} (is format=json enabled?)")
            resp.body?.string().orEmpty()
        }
        return parseSearxng(body, max)
    }

    internal fun parseSearxng(body: String, max: Int): List<SearchHit> {
        val root = json.parseToJsonElement(body).jsonObject
        val results = root["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val url = (o["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            SearchHit(
                title = (o["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                url = url,
                snippet = (o["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            )
        }.take(max)
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        fun parseDuckDuckGoHtml(html: String, max: Int): List<SearchHit> {
            val doc = Jsoup.parse(html)
            val hits = ArrayList<SearchHit>()
            for (result in doc.select("div.result, div.web-result")) {
                if (result.hasClass("result--ad")) continue
                val link = result.selectFirst("a.result__a") ?: continue
                val url = unwrapDuckDuckGoUrl(link.attr("href"))
                if (url.isBlank() || url.contains("duckduckgo.com/y.js")) continue
                val snippet = result.selectFirst(".result__snippet")?.text().orEmpty()
                hits += SearchHit(link.text(), url, snippet)
                if (hits.size >= max) break
            }
            return hits
        }

        fun parseDuckDuckGoLite(html: String, max: Int): List<SearchHit> {
            val doc = Jsoup.parse(html)
            val hits = ArrayList<SearchHit>()
            val links = doc.select("a.result-link")
            val snippets = doc.select("td.result-snippet")
            for ((i, link) in links.withIndex()) {
                val url = unwrapDuckDuckGoUrl(link.attr("href"))
                if (url.isBlank()) continue
                hits += SearchHit(link.text(), url, snippets.getOrNull(i)?.text().orEmpty())
                if (hits.size >= max) break
            }
            return hits
        }

        /** DuckDuckGo wraps targets as //duckduckgo.com/l/?uddg=<encoded>&rut=... */
        fun unwrapDuckDuckGoUrl(href: String): String {
            val absolute = if (href.startsWith("//")) "https:$href" else href
            val marker = "uddg="
            val idx = absolute.indexOf(marker)
            if (idx < 0) return absolute
            val end = absolute.indexOf('&', idx).let { if (it < 0) absolute.length else it }
            return URLDecoder.decode(absolute.substring(idx + marker.length, end), "UTF-8")
        }

        fun format(query: String, hits: List<SearchHit>): String = buildString {
            append("Results for \"").append(query).append("\":\n")
            hits.forEachIndexed { i, hit ->
                append(i + 1).append(". ").append(hit.title.ifBlank { hit.url }).append('\n')
                append("   ").append(hit.url).append('\n')
                if (hit.snippet.isNotBlank()) append("   ").append(hit.snippet).append('\n')
            }
        }.trimEnd()
    }
}

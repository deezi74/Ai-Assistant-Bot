package dev.pocketagent.feed

import dev.pocketagent.AppContainer
import dev.pocketagent.data.FeedPostEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Asks the agent for a handful of short posts steered by the user's feed sentence,
 * memory, goals and past likes/dislikes, then stores them in the Feed.
 */
class FeedGenerator(private val container: AppContainer) {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    suspend fun generate(count: Int = 5): Int {
        val s = container.settings.current
        val rated = container.database.content().ratedPosts()
        val liked = rated.filter { it.reaction > 0 }.joinToString("; ") { it.title }
        val disliked = rated.filter { it.reaction < 0 }.joinToString("; ") { it.title }
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm"))

        val prompt = buildString {
            append("Write my feed. It is ").append(now).append(".\n")
            append("My instruction for the feed: \"").append(s.feedSteer).append("\"\n")
            if (liked.isNotEmpty()) append("Posts I liked before: ").append(liked).append('\n')
            if (disliked.isNotEmpty()) append("Posts I disliked (avoid similar): ").append(disliked).append('\n')
            append("Use what you know about me from memory and my goals. ")
            if (!s.offlineMode) append("Use web_search for anything current (news, releases, weather, prices) and keep facts accurate. ")
            append("Then reply with ONLY a JSON array of ").append(count).append(" objects, no prose, no code fence:\n")
            append("""[{"emoji": "one emoji", "title": "max 8 words", "body": "2-3 short sentences", "url": "source url or empty"}]""")
        }
        val answer = container.runner.runOnce(prompt, maxSteps = 8)
        val posts = parse(answer)
        if (posts.isEmpty()) throw IllegalStateException("The model did not return feed posts. Try again or switch to a stronger model.")
        val created = System.currentTimeMillis()
        container.database.content().insertPosts(
            posts.map { p -> FeedPostEntity(emoji = p.emoji, title = p.title, body = p.body, url = p.url, createdAt = created) }
        )
        container.workspace.log("I", "feed: wrote ${posts.size} posts")
        return posts.size
    }

    data class Post(val emoji: String, val title: String, val body: String, val url: String?)

    fun parse(answer: String): List<Post> {
        val start = answer.indexOf('[')
        val end = answer.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val arr = try {
            json.parseToJsonElement(answer.substring(start, end + 1)) as? JsonArray
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val title = str("title")
            val body = str("body")
            if (title.isEmpty() || body.isEmpty()) return@mapNotNull null
            Post(str("emoji").ifEmpty { "✨" }.take(8), title, body, str("url").takeIf { it.startsWith("http") })
        }
    }
}

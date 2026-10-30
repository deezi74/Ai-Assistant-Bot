package dev.pocketagent.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class ParsedToolCall(val name: String, val arguments: JsonObject, val raw: String)

sealed interface StreamSegment {
    data class Text(val text: String) : StreamSegment
    data class Thought(val text: String) : StreamSegment
    data class Call(val call: ParsedToolCall) : StreamSegment
    data class MalformedCall(val raw: String, val error: String) : StreamSegment
}

/**
 * Incremental splitter for model output. Text outside tags is user-facing,
 * `<think>` blocks are routed to the agent terminal, and each
 * `<tool_call>{json}</tool_call>` becomes a [StreamSegment.Call] the moment the
 * closing tag arrives. Partial tags at chunk boundaries are held back, so a tag
 * split across tokens ("<tool" + "_call>") never leaks into the chat bubble.
 */
class ToolCallParser {
    private enum class State { TEXT, THINK, CALL }

    private val buffer = StringBuilder()
    private var state = State.TEXT

    fun feed(chunk: String): List<StreamSegment> {
        if (chunk.isEmpty()) return emptyList()
        buffer.append(chunk)
        return drain(final = false)
    }

    fun finish(): List<StreamSegment> = drain(final = true)

    private fun drain(final: Boolean): List<StreamSegment> {
        val out = ArrayList<StreamSegment>()
        while (true) {
            when (state) {
                State.TEXT -> {
                    val think = buffer.indexOf(THINK_OPEN)
                    val call = buffer.indexOf(CALL_OPEN)
                    val next = listOf(think, call).filter { it >= 0 }.minOrNull()
                    if (next != null) {
                        if (next > 0) out += StreamSegment.Text(buffer.substring(0, next))
                        if (next == call) {
                            buffer.delete(0, next + CALL_OPEN.length)
                            state = State.CALL
                        } else {
                            buffer.delete(0, next + THINK_OPEN.length)
                            state = State.THINK
                        }
                        continue
                    }
                    val keep = if (final) 0 else partialSuffix(buffer, listOf(THINK_OPEN, CALL_OPEN))
                    val emitLen = buffer.length - keep
                    if (emitLen > 0) {
                        out += StreamSegment.Text(buffer.substring(0, emitLen))
                        buffer.delete(0, emitLen)
                    }
                    return out
                }
                State.THINK -> {
                    val end = buffer.indexOf(THINK_CLOSE)
                    if (end >= 0) {
                        if (end > 0) out += StreamSegment.Thought(buffer.substring(0, end))
                        buffer.delete(0, end + THINK_CLOSE.length)
                        state = State.TEXT
                        continue
                    }
                    val keep = if (final) 0 else partialSuffix(buffer, listOf(THINK_CLOSE))
                    val emitLen = buffer.length - keep
                    if (emitLen > 0) {
                        out += StreamSegment.Thought(buffer.substring(0, emitLen))
                        buffer.delete(0, emitLen)
                    }
                    return out
                }
                State.CALL -> {
                    val end = buffer.indexOf(CALL_CLOSE)
                    if (end >= 0) {
                        val body = buffer.substring(0, end)
                        buffer.delete(0, end + CALL_CLOSE.length)
                        state = State.TEXT
                        out += parseCall(body)
                        continue
                    }
                    if (final) {
                        // Stream ended (stop sequence or EOS) inside the block: accept it if it parses.
                        val body = buffer.toString()
                        buffer.setLength(0)
                        state = State.TEXT
                        if (body.isNotBlank()) out += parseCall(body)
                    }
                    return out
                }
            }
        }
    }

    companion object {
        const val THINK_OPEN = "<think>"
        const val THINK_CLOSE = "</think>"
        const val CALL_OPEN = "<tool_call>"
        const val CALL_CLOSE = "</tool_call>"

        private val lenientJson = Json { isLenient = true; ignoreUnknownKeys = true }

        /** Length of the longest buffer suffix that is a proper prefix of any tag. */
        internal fun partialSuffix(buffer: CharSequence, tags: List<String>): Int {
            var best = 0
            for (tag in tags) {
                val max = minOf(tag.length - 1, buffer.length)
                for (len in max downTo 1) {
                    if (len <= best) break
                    var match = true
                    val start = buffer.length - len
                    for (i in 0 until len) {
                        if (buffer[start + i] != tag[i]) { match = false; break }
                    }
                    if (match) { best = len; break }
                }
            }
            return best
        }

        fun parseCall(rawBody: String): StreamSegment {
            var body = rawBody.trim()
            if (body.startsWith("```")) {
                body = body.removePrefix("```json").removePrefix("```").trim()
                if (body.endsWith("```")) body = body.removeSuffix("```").trim()
            }
            val first = body.indexOf('{')
            val last = body.lastIndexOf('}')
            if (first < 0 || last <= first) {
                return StreamSegment.MalformedCall(rawBody, "tool_call body is not a JSON object")
            }
            val jsonText = body.substring(first, last + 1)
            val obj = try {
                lenientJson.parseToJsonElement(jsonText).jsonObject
            } catch (e: Exception) {
                return StreamSegment.MalformedCall(rawBody, "invalid JSON: ${e.message?.take(160)}")
            }
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?: (obj["tool"] as? JsonPrimitive)?.contentOrNull?.trim()
            if (name.isNullOrEmpty()) return StreamSegment.MalformedCall(rawBody, "missing \"name\"")
            val argsElement = obj["arguments"] ?: obj["parameters"] ?: obj["args"]
            val args: JsonObject = when (argsElement) {
                null -> JsonObject(emptyMap())
                is JsonObject -> argsElement
                is JsonPrimitive -> {
                    val text = argsElement.contentOrNull.orEmpty().trim()
                    if (text.isEmpty()) JsonObject(emptyMap()) else try {
                        lenientJson.parseToJsonElement(text).jsonObject
                    } catch (e: Exception) {
                        buildJsonObject { put("input", text) }
                    }
                }
                else -> buildJsonObject { put("input", argsElement.toString()) }
            }
            return StreamSegment.Call(ParsedToolCall(name, args, rawBody))
        }
    }
}

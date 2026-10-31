package dev.pocketagent.agent

import dev.pocketagent.core.agent.AgentMode
import dev.pocketagent.core.agent.Tool
import dev.pocketagent.core.agent.ToolResult
import dev.pocketagent.core.agent.string
import dev.pocketagent.data.SecureSettings
import dev.pocketagent.data.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import kotlin.random.Random

private val bothModes = setOf(AgentMode.WIT, AgentMode.RESEARCH)

/** Lets the agent turn a procedure it worked out into a reusable skill. */
class LearnSkillTool(private val workspace: Workspace, private val learner: Learner) : Tool {
    override val name = "learn_skill"
    override val description =
        "Save a reusable skill after you worked out how to do a recurring kind of task, or when the user says \"remember how to do this\". " +
            "Skills are added to your instructions in every future chat."
    override val argumentsSignature = """{"name": string, "when": string, "instructions": string (numbered steps)}"""
    override val modes = bothModes

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val name = arguments.string("name")?.take(48) ?: return@withContext ToolResult.error("missing \"name\"")
        val whenToUse = arguments.string("when") ?: arguments.string("description") ?: ""
        val steps = arguments.string("instructions") ?: arguments.string("steps") ?: return@withContext ToolResult.error("missing \"instructions\"")
        val existing = workspace.skills().firstOrNull { it.name.lowercase(Locale.US) == name.lowercase(Locale.US) }
        workspace.saveSkill(existing, name, whenToUse.take(160).ifBlank { "Learned skill" }, steps.take(3000), true)
        val g = if (existing == null) learner.recordSkill() else learner.growth.value
        ToolResult(if (existing == null) "Learned the skill \"$name\" (level ${g.level})." else "Updated the skill \"$name\".")
    }
}

/** Lets the user change the assistant's look from chat: "turn yourself into a robot owl". */
class ChangeCharacterTool(
    private val generator: CharacterGenerator,
    private val workspace: Workspace,
    private val settings: SecureSettings
) : Tool {
    override val name = "change_character"
    override val description =
        "Redraw your own avatar as a new AI-generated character when the user asks you to look like something. " +
            "Styles: ${CharacterGenerator.styles.joinToString("|") { it.id }}."
    override val argumentsSignature = """{"description": string, "style"?: string}"""
    override val modes = bothModes

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val description = arguments.string("description") ?: arguments.string("look") ?: return ToolResult.error("missing \"description\"")
        val style = CharacterGenerator.style(arguments.string("style")?.lowercase(Locale.US))
        val source = settings.current.imageSource.takeIf { id -> generator.sources().any { it.id == id } } ?: CharacterGenerator.FREE
        return try {
            val bytes = generator.generate(description, style, Random.nextInt(1, 1_000_000), source)
            val file = workspace.saveCharacter(bytes, description)
            val avatar = workspace.importAvatarFile(file)
            settings.update { it.copy(avatarKind = "photo", avatarValue = avatar, characterDescription = description.take(300)) }
            ToolResult("Your avatar is now: $description (${style.label}). The user can see it in the header.")
        } catch (e: Exception) {
            ToolResult.error("could not draw the character: ${e.message}")
        }
    }
}

/**
 * Makes pictures in the chat ("imagine…") and edits the photo the user just
 * attached. The image is saved to the media folder and shown in the chat.
 */
class ImagineTool(
    private val generator: CharacterGenerator,
    private val workspace: Workspace,
    private val settings: SecureSettings,
    private val attachedImage: () -> java.io.File?
) : Tool {
    override val name = "imagine"
    override val description =
        "Create an image from a description, or edit the photo the user attached this turn (edit: true). " +
            "Write a vivid, specific prompt (subject, setting, style, lighting). The picture appears in the chat by itself; reply with one short line."
    override val argumentsSignature = """{"prompt": string, "shape"?: "square"|"portrait"|"landscape", "edit"?: bool}"""
    override val modes = bothModes
    override val timeoutMs: Long = 200_000

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val prompt = arguments.string("prompt") ?: arguments.string("description") ?: return ToolResult.error("missing \"prompt\"")
        val edit = arguments["edit"]?.toString()?.trim('"') == "true"
        val input = if (edit) attachedImage() ?: return ToolResult.error("no photo is attached to edit; ask the user to attach one with +") else null
        val (w, h) = when (arguments.string("shape")?.lowercase(Locale.US)) {
            "portrait", "tall", "vertical" -> 768 to 1152
            "landscape", "wide", "horizontal" -> 1152 to 768
            else -> 1024 to 1024
        }
        val source = settings.current.imageSource.takeIf { id -> generator.sources().any { it.id == id } } ?: CharacterGenerator.FREE
        return try {
            val inputBytes = input?.let { workspace.jpegBase64(it, 1024) }?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
            val bytes = generator.image(prompt, w, h, Random.nextInt(1, 1_000_000), source, inputBytes)
            val file = java.io.File(workspace.mediaDir, "imagine_${System.currentTimeMillis()}.jpg")
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { file.writeBytes(bytes) }
            ToolResult("${if (edit) "Edited" else "Created"} the image (shown to the user).\n${IMAGE_MARKER}${file.name}]")
        } catch (e: Exception) {
            ToolResult.error("could not make the image: ${e.message}")
        }
    }

    companion object {
        const val IMAGE_MARKER = "[image:"
    }
}

/**
 * Makes short AI videos ("make a video of…"), optionally animating the photo the
 * user attached this turn. Needs a Gemini or Pollinations key; the MP4 is saved to
 * the media folder and plays in the chat.
 */
class MakeVideoTool(
    private val maker: VideoMaker,
    private val workspace: Workspace,
    private val settings: SecureSettings,
    private val attachedImage: () -> java.io.File?
) : Tool {
    override val name = "make_video"
    override val description =
        "Make a short AI video (about 4 to 8 seconds, with sound) from a description, or animate the photo the user attached (from_photo: true). " +
            "Write a cinematic prompt: subject, action, setting, camera move, lighting, mood, sound. It takes one to three minutes; " +
            "the video appears in the chat by itself, so reply with one short line."
    override val argumentsSignature = """{"prompt": string, "shape"?: "portrait"|"landscape", "seconds"?: int, "from_photo"?: bool}"""
    override val modes = bothModes
    override val timeoutMs: Long = 9 * 60_000L

    override suspend fun execute(arguments: JsonObject): ToolResult {
        if (!maker.ready()) return ToolResult.error(VideoMaker.NEEDS_KEY + " Tell the user exactly that.")
        val prompt = arguments.string("prompt") ?: arguments.string("description") ?: return ToolResult.error("missing \"prompt\"")
        val fromPhoto = arguments["from_photo"]?.toString()?.trim('"') == "true"
        val photo = if (fromPhoto) attachedImage() ?: return ToolResult.error("no photo is attached; ask the user to attach one with +") else null
        val vertical = when (arguments.string("shape")?.lowercase(Locale.US)) {
            "landscape", "wide", "horizontal" -> false
            else -> true
        }
        val seconds = arguments.string("seconds")?.toIntOrNull() ?: 8
        return try {
            val bytes = photo?.let { workspace.jpegBase64(it, 1280) }?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
            val file = java.io.File(workspace.mediaDir, "video_${System.currentTimeMillis()}.mp4")
            val source = settings.current.videoSource.takeIf { it != "auto" }
            maker.make(prompt, vertical, seconds, bytes, file, source)
            ToolResult("Made the video (it plays in the chat).\n${VIDEO_MARKER}${file.name}]")
        } catch (e: Exception) {
            ToolResult.error("could not make the video: ${e.message}")
        }
    }

    companion object {
        const val VIDEO_MARKER = "[video:"

        /** The media file named by an image or video marker in a tool output. */
        fun mediaFrom(output: String): String? =
            Regex("\\[(?:image|video):([A-Za-z0-9_.-]+)]").find(output)?.groupValues?.get(1)
    }
}

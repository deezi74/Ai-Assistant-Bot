package dev.pocketagent.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Skill(val fileName: String, val name: String, val description: String, val instructions: String, val enabled: Boolean)

data class LogLine(val time: Long, val level: String, val text: String)

/**
 * File-backed stores that sit next to the Room database: memory.md, skills (one .md each),
 * Library files, media, the custom avatar photo and an in-memory log ring.
 */
class Workspace(private val context: Context) {
    val root: File get() = context.filesDir
    val personaDir: File get() = File(root, "persona").apply { mkdirs() }
    val memoryFile: File get() = File(personaDir, "memory.md")
    val skillsDir: File get() = File(personaDir, "skills").apply { mkdirs() }
    val artifactsDir: File get() = File(context.getExternalFilesDir(null) ?: root, "artifacts").apply { mkdirs() }
    val mediaDir: File get() = File(context.getExternalFilesDir(null) ?: root, "media").apply { mkdirs() }
    val avatarDir: File get() = File(root, "avatar").apply { mkdirs() }

    private val _avatarVersion = MutableStateFlow(0)
    /** Bumps whenever the avatar photo changes so image composables reload. */
    val avatarVersion: StateFlow<Int> = _avatarVersion.asStateFlow()

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()

    fun log(level: String, text: String) {
        val line = LogLine(System.currentTimeMillis(), level, text)
        _logs.value = (_logs.value + line).takeLast(MAX_LOG_LINES)
        android.util.Log.println(
            when (level) {
                "E" -> android.util.Log.ERROR
                "W" -> android.util.Log.WARN
                else -> android.util.Log.INFO
            },
            "TruAgent", text
        )
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    // region Memory

    fun readMemory(): String = if (memoryFile.exists()) memoryFile.readText() else ""

    fun writeMemory(text: String) {
        memoryFile.writeText(text)
    }

    @Synchronized
    fun appendMemory(fact: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val existing = readMemory()
        val prefix = if (existing.isBlank()) "# Memory\n\n" else if (existing.endsWith("\n")) "" else "\n"
        memoryFile.appendText("$prefix- $fact ($stamp)\n")
    }

    // endregion

    // region Skills

    fun skills(): List<Skill> = skillsDir.listFiles { f -> f.isFile && f.name.endsWith(".md") }
        ?.sortedBy { it.name }
        ?.map { parseSkill(it) }
        .orEmpty()

    fun saveSkill(original: Skill?, name: String, description: String, instructions: String, enabled: Boolean) {
        val slug = name.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "skill" }
        val file = File(skillsDir, "$slug.md")
        if (original != null && original.fileName != file.name) File(skillsDir, original.fileName).delete()
        file.writeText(
            buildString {
                append("---\n")
                append("name: ").append(name.replace("\n", " ")).append('\n')
                append("description: ").append(description.replace("\n", " ")).append('\n')
                append("enabled: ").append(enabled).append('\n')
                append("---\n\n")
                append(instructions.trim()).append('\n')
            }
        )
    }

    fun deleteSkill(skill: Skill) {
        File(skillsDir, skill.fileName).delete()
    }

    fun setSkillEnabled(skill: Skill, enabled: Boolean) =
        saveSkill(skill, skill.name, skill.description, skill.instructions, enabled)

    private fun parseSkill(file: File): Skill {
        val text = file.readText()
        var name = file.nameWithoutExtension
        var description = ""
        var enabled = true
        var body = text
        if (text.startsWith("---")) {
            val end = text.indexOf("\n---", 3)
            if (end > 0) {
                text.substring(3, end).lines().forEach { line ->
                    val key = line.substringBefore(':').trim()
                    val value = line.substringAfter(':', "").trim()
                    when (key) {
                        "name" -> if (value.isNotEmpty()) name = value
                        "description" -> description = value
                        "enabled" -> enabled = value != "false"
                    }
                }
                body = text.substring(end + 4).trim()
            }
        }
        return Skill(file.name, name, description, body, enabled)
    }

    fun ensureDefaultSkills() {
        if (skillsDir.listFiles()?.isNotEmpty() == true) return
        saveSkill(
            null, "Morning brief", "Short weather + news + plan for the day when asked for a brief",
            "When the user asks for a brief or a morning update: use web_search for local weather and 3 top headlines, " +
                "check their tracked goals, and answer in at most 6 lines. End with one suggested first task.",
            true
        )
        saveSkill(
            null, "Trip planner", "Day-by-day itineraries saved to the Library",
            "When the user names a destination and a number of days: research opening hours, travel times and weather, " +
                "then call save_artifact with a day-by-day markdown plan titled '<City> itinerary'. Summarise it in chat in 4 lines.",
            true
        )
    }

    // endregion

    // region Files

    fun safeFileName(title: String, ext: String): String {
        val base = title.replace(Regex("[^A-Za-z0-9 _.-]"), "").trim().replace(' ', '-').take(60).ifEmpty { "artifact" }
        var name = "$base.$ext"
        var i = 2
        while (File(artifactsDir, name).exists()) {
            name = "$base-$i.$ext"
            i++
        }
        return name
    }

    /** Copies a picked image into media/, downscaled to ≤1600px, and returns the file. */
    suspend fun importImage(uri: Uri): File = withContext(Dispatchers.IO) {
        val bitmap = decodeScaled(uri, 1600)
        val file = File(mediaDir, "img-${System.currentTimeMillis()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bitmap.recycle()
        file
    }

    /** Stores a square-cropped avatar photo and returns its file name. */
    suspend fun importAvatar(uri: Uri): String = withContext(Dispatchers.IO) {
        val bitmap = decodeScaled(uri, 768)
        val side = minOf(bitmap.width, bitmap.height)
        val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        avatarDir.listFiles()?.forEach { it.delete() }
        val name = "avatar-${System.currentTimeMillis()}.jpg"
        File(avatarDir, name).outputStream().use { square.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        if (square !== bitmap) square.recycle()
        bitmap.recycle()
        _avatarVersion.value = _avatarVersion.value + 1
        name
    }

    fun avatarFile(name: String): File = File(avatarDir, name)

    fun jpegBase64(file: File, maxDim: Int = 1024): String {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > maxDim * 2) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return ""
        val scaled = scaleDown(bmp, maxDim)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun decodeScaled(uri: Uri, maxDim: Int): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim * 2) sample *= 2
        val raw = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("Could not read image")
        val rotation = try {
            resolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            0f
        }
        val rotated = if (rotation != 0f) {
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rotation) }, true)
                .also { if (it !== raw) raw.recycle() }
        } else {
            raw
        }
        return scaleDown(rotated, maxDim).also { if (it !== rotated) rotated.recycle() }
    }

    private fun scaleDown(bmp: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= maxDim) return bmp
        val ratio = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(bmp, (bmp.width * ratio).toInt(), (bmp.height * ratio).toInt(), true)
    }

    // region Mounted folders (Storage Access Framework trees the user granted)

    private val mountsFile: File get() = File(root, "mounts.txt")

    fun mounts(): List<String> = if (mountsFile.exists()) mountsFile.readLines().filter { it.isNotBlank() } else emptyList()

    @Synchronized
    fun addMount(uri: String) {
        if (uri in mounts()) return
        mountsFile.writeText((mounts() + uri).joinToString("\n"))
    }

    @Synchronized
    fun removeMount(uri: String) {
        mountsFile.writeText(mounts().filter { it != uri }.joinToString("\n"))
    }

    // endregion

    fun dirSize(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // endregion

    companion object {
        private const val MAX_LOG_LINES = 500
    }
}

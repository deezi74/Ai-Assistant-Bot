package dev.pocketagent.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Zip backup of everything that is not a secret: chats database, soul/memory/skills,
 * avatar, Library files and plain settings. API keys and env vars stay on the device.
 */
class Backup(
    private val context: Context,
    private val database: AppDatabase,
    private val workspace: Workspace,
    private val settings: SecureSettings
) {
    suspend fun export(target: Uri): Int = withContext(Dispatchers.IO) {
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        var count = 0
        val out = context.contentResolver.openOutputStream(target) ?: throw IllegalStateException("Cannot write to the chosen file")
        ZipOutputStream(out.buffered()).use { zip ->
            fun add(name: String, file: File) {
                if (!file.isFile) return
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                count++
            }
            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write(settings.exportPublic().toByteArray())
            zip.closeEntry()
            add("db/${AppDatabase.NAME}", context.getDatabasePath(AppDatabase.NAME))
            workspace.personaDir.walkTopDown().filter { it.isFile }.forEach {
                add("persona/" + it.relativeTo(workspace.personaDir).invariantSeparatorsPath, it)
            }
            workspace.avatarDir.listFiles()?.forEach { add("avatar/${it.name}", it) }
            workspace.artifactsDir.listFiles()?.forEach { add("artifacts/${it.name}", it) }
            workspace.mediaDir.listFiles()?.forEach { add("media/${it.name}", it) }
        }
        count
    }

    /** Restores a backup. Returns true when the database was replaced and the app must restart. */
    suspend fun restore(source: Uri): Boolean = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(source) ?: throw IllegalStateException("Cannot open the chosen file")
        var dbBytes: File? = null
        var settingsJson: String? = null
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory || name.contains("..")) continue
                when {
                    name == "settings.json" -> settingsJson = zip.readBytes().decodeToString()
                    name.startsWith("db/") -> {
                        val tmp = File(context.cacheDir, "restore.db")
                        tmp.outputStream().use { zip.copyTo(it) }
                        dbBytes = tmp
                    }
                    name.startsWith("persona/") -> write(File(workspace.personaDir, name.removePrefix("persona/")), zip)
                    name.startsWith("avatar/") -> write(File(workspace.avatarDir, name.removePrefix("avatar/")), zip)
                    name.startsWith("artifacts/") -> write(File(workspace.artifactsDir, name.removePrefix("artifacts/")), zip)
                    name.startsWith("media/") -> write(File(workspace.mediaDir, name.removePrefix("media/")), zip)
                }
            }
        }
        settingsJson?.let { settings.importPublic(it) }
        val db = dbBytes ?: return@withContext false
        database.close()
        val path = context.getDatabasePath(AppDatabase.NAME)
        File(path.path + "-wal").delete()
        File(path.path + "-shm").delete()
        db.copyTo(path, overwrite = true)
        db.delete()
        true
    }

    private fun write(file: File, zip: ZipInputStream) {
        file.parentFile?.mkdirs()
        file.outputStream().use { zip.copyTo(it) }
    }
}

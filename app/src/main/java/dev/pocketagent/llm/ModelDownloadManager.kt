package dev.pocketagent.llm

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.pocketagent.PocketAgentApp
import dev.pocketagent.R
import dev.pocketagent.core.llm.HfFile
import dev.pocketagent.core.llm.HfModelSummary
import dev.pocketagent.core.llm.HfReference
import dev.pocketagent.core.llm.HuggingFaceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class DownloadStatus(
    val id: UUID,
    val fileName: String,
    val state: WorkInfo.State,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val error: String?
) {
    val fraction: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    val isActive: Boolean
        get() = state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED
}

data class LocalModelFile(val file: File) {
    val name: String get() = file.name
    val sizeBytes: Long get() = file.length()
}

/**
 * Hugging Face → device pipeline. Resolves repo strings, lists their .gguf files and
 * runs each download as an expedited-if-possible foreground WorkManager job that
 * survives app death, resumes from `.part` files and reports byte-level progress.
 */
class ModelDownloadManager(private val context: Context) {
    private val workManager = WorkManager.getInstance(context)
    private val hf = HuggingFaceClient()

    val modelsDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }

    val downloads: Flow<List<DownloadStatus>> = workManager.getWorkInfosByTagFlow(TAG).map { infos ->
        infos.map { info ->
            val fileName = info.tags.firstOrNull { it.startsWith(FILE_TAG_PREFIX) }?.removePrefix(FILE_TAG_PREFIX) ?: "model.gguf"
            DownloadStatus(
                id = info.id,
                fileName = fileName,
                state = info.state,
                downloadedBytes = info.progress.getLong(KEY_DONE, 0L),
                totalBytes = info.progress.getLong(KEY_TOTAL, -1L),
                error = info.outputData.getString(KEY_ERROR)
            )
        }.sortedByDescending { it.isActive }
    }

    fun localModels(): List<LocalModelFile> =
        modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            ?.map { LocalModelFile(it) }
            .orEmpty()

    fun delete(model: LocalModelFile): Boolean = model.file.delete()

    suspend fun search(query: String): List<HfModelSummary> = withContext(Dispatchers.IO) {
        hf.searchGgufModels(query, 25, settingsToken())
    }

    suspend fun listFiles(repoInput: String): Pair<HfReference, List<HfFile>> = withContext(Dispatchers.IO) {
        val ref = HfReference.parse(repoInput)
        val files = hf.listGgufFiles(ref.repo, ref.revision, settingsToken())
        if (files.isEmpty()) throw IOException("No .gguf files in ${ref.repo}")
        ref to files
    }

    fun enqueue(ref: HfReference, file: HfFile): UUID {
        val fileName = file.path.substringAfterLast('/')
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(
                workDataOf(
                    KEY_URL to hf.resolveUrl(ref, file.path),
                    KEY_FILE to fileName,
                    KEY_EXPECTED to file.sizeBytes
                )
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag(FILE_TAG_PREFIX + fileName)
            .build()
        workManager.enqueueUniqueWork("download:$fileName", ExistingWorkPolicy.KEEP, request)
        return request.id
    }

    fun cancel(id: UUID) {
        workManager.cancelWorkById(id)
    }

    fun clearFinished() {
        workManager.pruneWork()
    }

    /** Copies a user-picked document (SAF) into the models dir so llama.cpp can mmap it by path. */
    suspend fun importFromUri(uri: Uri, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "imported.gguf"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (!name.endsWith(".gguf", ignoreCase = true)) throw IOException("$name is not a .gguf file")
        val dest = File(modelsDir, name)
        val tmp = File(modelsDir, "$name.importing")
        resolver.openInputStream(uri).use { input ->
            if (input == null) throw IOException("Cannot open $name")
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var copied = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    copied += n
                    if (size > 0) onProgress(copied.toFloat() / size)
                }
            }
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw IOException("Could not move import into place")
        onProgress(1f)
        dest
    }

    private fun settingsToken(): String? =
        (context.applicationContext as PocketAgentApp).container.settings.huggingFaceToken

    companion object {
        const val TAG = "model-download"
        const val FILE_TAG_PREFIX = "file:"
        const val KEY_URL = "url"
        const val KEY_FILE = "file"
        const val KEY_EXPECTED = "expected"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        const val KEY_PATH = "path"
    }
}

class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val notificationId = id.hashCode()
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        val url = inputData.getString(ModelDownloadManager.KEY_URL) ?: return Result.failure()
        val fileName = inputData.getString(ModelDownloadManager.KEY_FILE) ?: return Result.failure()
        val expected = inputData.getLong(ModelDownloadManager.KEY_EXPECTED, -1L)
        val app = applicationContext as PocketAgentApp
        val dest = File(app.container.downloads.modelsDir, fileName)

        try {
            setForeground(foregroundInfo(fileName, 0, expected))
        } catch (e: IllegalStateException) {
            // Background start restrictions (Android 12+ when not expedited): continue as a normal job.
        }

        return try {
            var lastNotify = 0L
            withContext(Dispatchers.IO) {
                HuggingFaceClient().download(url, dest, app.container.settings.huggingFaceToken) { done, total ->
                    val effectiveTotal = if (total > 0) total else expected
                    setProgressAsync(
                        workDataOf(
                            ModelDownloadManager.KEY_DONE to done,
                            ModelDownloadManager.KEY_TOTAL to effectiveTotal
                        )
                    )
                    val now = System.currentTimeMillis()
                    if (now - lastNotify > 1000) {
                        lastNotify = now
                        notificationManager.notify(notificationId, buildNotification(fileName, done, effectiveTotal))
                    }
                    !isStopped
                }
            }
            Result.success(workDataOf(ModelDownloadManager.KEY_PATH to dest.absolutePath))
        } catch (e: HuggingFaceClient.DownloadCancelledException) {
            Result.failure(workDataOf(ModelDownloadManager.KEY_ERROR to "Cancelled"))
        } catch (e: IOException) {
            val permanent = e.message?.contains("HTTP 4") == true
            if (!permanent && runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(workDataOf(ModelDownloadManager.KEY_ERROR to (e.message ?: "Download failed")))
            }
        } finally {
            notificationManager.cancel(notificationId)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val fileName = inputData.getString(ModelDownloadManager.KEY_FILE) ?: "model"
        return foregroundInfo(fileName, 0, inputData.getLong(ModelDownloadManager.KEY_EXPECTED, -1L))
    }

    private fun foregroundInfo(fileName: String, done: Long, total: Long): ForegroundInfo {
        val notification = buildNotification(fileName, done, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun buildNotification(fileName: String, done: Long, total: Long) =
        NotificationCompat.Builder(applicationContext, PocketAgentApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.notif_download_title))
            .setContentText(
                if (total > 0) "$fileName · ${done / 1_048_576} / ${total / 1_048_576} MB" else "$fileName · ${done / 1_048_576} MB"
            )
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(
                if (total > 0) 1000 else 0,
                if (total > 0) ((done * 1000) / total).toInt() else 0,
                total <= 0
            )
            .addAction(0, applicationContext.getString(R.string.action_stop), WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()

    companion object {
        private const val MAX_ATTEMPTS = 5
    }
}

package dev.pocketagent.routines

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.pocketagent.MainActivity
import dev.pocketagent.PocketAgentApp
import dev.pocketagent.R
import dev.pocketagent.data.ArtifactEntity
import dev.pocketagent.data.RoutineEntity
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Schedules each enabled routine as a one-shot job at its next run time; the job re-arms itself. */
class RoutineScheduler(private val context: Context) {
    private val workManager = WorkManager.getInstance(context)

    fun nextRun(r: RoutineEntity, from: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        var candidate = from.withHour(r.hour).withMinute(r.minute).withSecond(0).withNano(0)
        if (!candidate.isAfter(from)) candidate = candidate.plusDays(1)
        repeat(8) {
            val ok = when (r.repeat) {
                "weekdays" -> candidate.dayOfWeek != DayOfWeek.SATURDAY && candidate.dayOfWeek != DayOfWeek.SUNDAY
                "weekly" -> candidate.dayOfWeek.value == r.dayOfWeek
                else -> true
            }
            if (ok) return candidate
            candidate = candidate.plusDays(1)
        }
        return candidate
    }

    fun describe(r: RoutineEntity): String {
        val time = "%02d:%02d".format(r.hour, r.minute)
        val cadence = when (r.repeat) {
            "weekdays" -> "Weekdays $time"
            "weekly" -> "${DayOfWeek.of(r.dayOfWeek.coerceIn(1, 7)).name.lowercase().replaceFirstChar { it.uppercase() }}s $time"
            else -> "Daily $time"
        }
        if (!r.enabled) return "$cadence · paused"
        return "$cadence · next " + nextRun(r).format(DateTimeFormatter.ofPattern("MMM d HH:mm"))
    }

    fun schedule(r: RoutineEntity) {
        val name = workName(r.id)
        if (!r.enabled) {
            workManager.cancelUniqueWork(name)
            return
        }
        val now = LocalDateTime.now()
        val delayMs = java.time.Duration.between(now.atZone(ZoneId.systemDefault()), nextRun(r, now).atZone(ZoneId.systemDefault())).toMillis()
        val request = OneTimeWorkRequestBuilder<RoutineWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_ID to r.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    }

    fun runNow(r: RoutineEntity) {
        val request = OneTimeWorkRequestBuilder<RoutineWorker>()
            .setInputData(workDataOf(KEY_ID to r.id, KEY_ONE_OFF to true))
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(workName(r.id) + ":now", ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(id: Long) = workManager.cancelUniqueWork(workName(id))

    suspend fun rescheduleAll() {
        val container = (context.applicationContext as PocketAgentApp).container
        val dao = container.database.content()
        if (dao.routines().isEmpty()) {
            dao.insertRoutine(
                RoutineEntity(name = "Write the feed", kind = "feed", prompt = "", hour = 8, minute = 0)
            )
        }
        dao.routines().forEach { schedule(it) }
    }

    private fun workName(id: Long) = "routine:$id"

    companion object {
        const val TAG = "routine"
        const val KEY_ID = "id"
        const val KEY_ONE_OFF = "oneOff"
    }
}

class RoutineWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PocketAgentApp
        val container = app.container
        val dao = container.database.content()
        val id = inputData.getLong(RoutineScheduler.KEY_ID, -1)
        val routine = dao.routine(id) ?: return Result.success()
        val oneOff = inputData.getBoolean(RoutineScheduler.KEY_ONE_OFF, false)
        if (!routine.enabled && !oneOff) return Result.success()

        container.ensureModelLoaded()
        val result = try {
            if (routine.kind == "feed") {
                val n = container.feed.generate()
                "Wrote $n feed posts"
            } else {
                val answer = container.runner.runOnce(routine.prompt)
                val fileName = container.workspace.safeFileName("${routine.name} ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))}", "md")
                val file = File(container.workspace.artifactsDir, fileName)
                file.writeText("# ${routine.name}\n\n$answer\n")
                dao.insertArtifact(ArtifactEntity(title = routine.name, fileName = fileName, format = "md", sizeBytes = file.length(), createdAt = System.currentTimeMillis()))
                notify(routine.name, answer.take(300))
                answer.take(200)
            }
        } catch (e: Exception) {
            container.workspace.log("E", "routine ${routine.name} failed: ${e.message}")
            "Failed: ${e.message}"
        }
        dao.updateRoutine(routine.copy(lastRunAt = System.currentTimeMillis(), lastResult = result))
        if (!oneOff) container.routines.schedule(routine)
        return Result.success()
    }

    private fun notify(title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            applicationContext, 7, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(applicationContext, PocketAgentApp.CHANNEL_ROUTINES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(title.hashCode(), n)
    }
}

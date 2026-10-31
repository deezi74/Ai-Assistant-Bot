package dev.pocketagent.agent

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import dev.pocketagent.core.agent.AgentMode
import dev.pocketagent.core.agent.Tool
import dev.pocketagent.core.agent.ToolResult
import dev.pocketagent.core.agent.int
import dev.pocketagent.core.agent.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

/**
 * Everyday phone actions through public Android APIs: flashlight, volume, brightness,
 * Do Not Disturb, alarms and timers, opening apps and settings panels, media keys,
 * dialer and SMS drafts (the user still presses call/send), clipboard and battery.
 */
class PhoneTool(private val context: Context, private val enabled: () -> Boolean) : Tool {
    override val name = "phone"
    override val description =
        "Control this phone. Actions: flashlight (state on/off/toggle), volume (stream media/ring/alarm/notification, level 0-100 or up/down/mute), " +
            "brightness (level 0-100), dnd (state on/off), alarm (hour 0-23, minute, label), timer (seconds, label), open_app (name), " +
            "open_settings (page wifi/bluetooth/internet/display/sound/battery/location/nfc/apps/notifications), media (play_pause/next/previous), " +
            "dial (number), sms (number, text), open_url (url), map (query), clipboard (text), battery."
    override val argumentsSignature =
        """{"action": string, "state"?: string, "level"?: number|string, "stream"?: string, "hour"?: number, "minute"?: number, "seconds"?: number, "label"?: string, "name"?: string, "page"?: string, "number"?: string, "text"?: string, "url"?: string, "query"?: string}"""
    override val modes = setOf(AgentMode.WIT, AgentMode.RESEARCH)

    override fun isAvailable(): Boolean = enabled()

    private val cameraManager get() = context.getSystemService(CameraManager::class.java)
    private val audio get() = context.getSystemService(AudioManager::class.java)
    @Volatile private var torchOn = false
    private var torchCallbackRegistered = false

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.Main) {
        if (!enabled()) return@withContext ToolResult.error("phone controls are turned off in Settings ▸ Phone controls")
        val action = (arguments.string("action") ?: return@withContext ToolResult.error("missing \"action\"")).lowercase().trim()
        try {
            when (action) {
                "flashlight", "torch", "flash" -> flashlight(arguments.string("state") ?: "toggle")
                "volume" -> volume(arguments.string("stream") ?: "media", arguments.string("level") ?: arguments.int("level")?.toString() ?: "up")
                "brightness" -> brightness(arguments.int("level") ?: arguments.string("level")?.filter { it.isDigit() }?.toIntOrNull())
                "dnd", "do_not_disturb" -> dnd(arguments.string("state") ?: "on")
                "alarm" -> alarm(arguments.int("hour"), arguments.int("minute") ?: 0, arguments.string("label"))
                "timer" -> timer(arguments.int("seconds"), arguments.string("label"))
                "open_app", "launch" -> openApp(arguments.string("name") ?: arguments.string("query"))
                "open_settings", "settings" -> openSettings(arguments.string("page") ?: "")
                "media" -> media(arguments.string("state") ?: arguments.string("name") ?: arguments.string("query") ?: "play_pause")
                "play_pause", "next", "previous" -> media(action)
                "dial", "call" -> start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + (arguments.string("number") ?: "").filter { it.isDigit() || it == '+' })), "Dialer opened. The user presses call.")
                "sms", "text_message" -> start(
                    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + (arguments.string("number") ?: "").filter { it.isDigit() || it == '+' }))
                        .putExtra("sms_body", arguments.string("text").orEmpty()),
                    "Message draft opened. The user presses send."
                )
                "open_url", "browse" -> {
                    val url = arguments.string("url") ?: return@withContext ToolResult.error("missing \"url\"")
                    start(Intent(Intent.ACTION_VIEW, Uri.parse(if (url.startsWith("http")) url else "https://$url")), "Opened $url")
                }
                "map", "navigate" -> {
                    val q = arguments.string("query") ?: arguments.string("text") ?: return@withContext ToolResult.error("missing \"query\"")
                    start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))), "Map opened for $q")
                }
                "clipboard", "copy" -> {
                    val text = arguments.string("text") ?: return@withContext ToolResult.error("missing \"text\"")
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PocketAgent", text))
                    ToolResult("Copied ${text.length} characters to the clipboard.")
                }
                "battery" -> battery()
                else -> ToolResult.error("unknown action \"$action\"")
            }
        } catch (e: SecurityException) {
            ToolResult.error("Android blocked $action: ${e.message}. Check Settings ▸ Phone controls.")
        } catch (e: Exception) {
            ToolResult.error("$action failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun ensureTorchCallback() {
        if (torchCallbackRegistered) return
        cameraManager.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                torchOn = enabled
            }
        }, Handler(Looper.getMainLooper()))
        torchCallbackRegistered = true
    }

    private fun flashlight(state: String): ToolResult {
        val cm = cameraManager
        val id = cm.cameraIdList.firstOrNull { cid ->
            cm.getCameraCharacteristics(cid).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return ToolResult.error("this phone has no flashlight")
        ensureTorchCallback()
        val on = when (state.lowercase()) {
            "on", "true", "1" -> true
            "off", "false", "0" -> false
            else -> !torchOn
        }
        cm.setTorchMode(id, on)
        torchOn = on
        return ToolResult("Flashlight ${if (on) "on" else "off"}.")
    }

    private fun volume(streamName: String, level: String): ToolResult {
        val stream = when (streamName.lowercase()) {
            "ring", "ringer", "ringtone" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification", "notifications" -> AudioManager.STREAM_NOTIFICATION
            "call", "voice" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val am = audio
        val max = am.getStreamMaxVolume(stream)
        when (level.lowercase().trim()) {
            "up", "louder" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "down", "quieter" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute", "0" -> am.setStreamVolume(stream, 0, AudioManager.FLAG_SHOW_UI)
            "max" -> am.setStreamVolume(stream, max, AudioManager.FLAG_SHOW_UI)
            else -> {
                val percent = level.filter { it.isDigit() }.toIntOrNull()?.coerceIn(0, 100)
                    ?: return ToolResult.error("level must be 0-100, up, down, mute or max")
                am.setStreamVolume(stream, (max * percent / 100f).roundToInt(), AudioManager.FLAG_SHOW_UI)
            }
        }
        val now = am.getStreamVolume(stream)
        return ToolResult("${streamName.lowercase()} volume is now ${if (max > 0) now * 100 / max else 0}%.")
    }

    private fun brightness(level: Int?): ToolResult {
        val percent = level?.coerceIn(0, 100) ?: return ToolResult.error("give a brightness level 0-100")
        if (!Settings.System.canWrite(context)) {
            start(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")), "")
            return ToolResult.error("PocketAgent needs \"Modify system settings\" to change brightness. I opened that screen; allow it and ask again.")
        }
        val resolver = context.contentResolver
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, (255 * percent / 100f).roundToInt().coerceIn(1, 255))
        return ToolResult("Brightness set to $percent% (auto-brightness off).")
    }

    private fun dnd(state: String): ToolResult {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            start(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), "")
            return ToolResult.error("PocketAgent needs Do Not Disturb access. I opened that screen; allow it and ask again.")
        }
        val on = state.lowercase() !in setOf("off", "false", "0")
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return ToolResult("Do Not Disturb ${if (on) "on (priority only)" else "off"}.")
    }

    private fun alarm(hour: Int?, minute: Int, label: String?): ToolResult {
        val h = hour?.coerceIn(0, 23) ?: return ToolResult.error("give hour (0-23) and minute")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!label.isNullOrBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return start(intent, "Alarm set for %02d:%02d%s.".format(h, minute.coerceIn(0, 59), if (label.isNullOrBlank()) "" else " ($label)"))
    }

    private fun timer(seconds: Int?, label: String?): ToolResult {
        val s = seconds?.takeIf { it > 0 } ?: return ToolResult.error("give the timer length in seconds")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, s.coerceAtMost(86_400))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!label.isNullOrBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        val pretty = if (s >= 60) "${s / 60} min" + (if (s % 60 > 0) " ${s % 60} s" else "") else "$s s"
        return start(intent, "Timer started for $pretty.")
    }

    private fun openApp(query: String?): ToolResult {
        val q = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return ToolResult.error("missing app \"name\"")
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcher, 0)
        }
        val labelled = apps.map { it to it.loadLabel(pm).toString() }
        val match = labelled.firstOrNull { it.second.lowercase() == q }
            ?: labelled.firstOrNull { it.second.lowercase().startsWith(q) }
            ?: labelled.firstOrNull { it.second.lowercase().contains(q) }
            ?: labelled.firstOrNull { it.first.activityInfo.packageName.lowercase().contains(q.replace(" ", "")) }
            ?: return ToolResult.error("no installed app matches \"$query\"")
        val intent = pm.getLaunchIntentForPackage(match.first.activityInfo.packageName)
            ?: return ToolResult.error("${match.second} can't be opened directly")
        return start(intent, "Opened ${match.second}.")
    }

    private fun openSettings(page: String): ToolResult {
        val p = page.lowercase()
        val panel = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val intent = when {
            p.contains("wifi") || p.contains("wi-fi") -> Intent(if (panel) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS)
            p.contains("internet") || p.contains("data") || p.contains("mobile") -> Intent(if (panel) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS)
            p.contains("nfc") -> Intent(if (panel) Settings.Panel.ACTION_NFC else Settings.ACTION_NFC_SETTINGS)
            p.contains("volume") || p.contains("sound") -> Intent(if (panel) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS)
            p.contains("bluetooth") -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            p.contains("display") || p.contains("screen") -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            p.contains("battery") || p.contains("power") -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
            p.contains("location") || p.contains("gps") -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            p.contains("airplane") || p.contains("flight") -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            p.contains("notification") -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            p.contains("app") -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
            p.contains("date") || p.contains("time") -> Intent(Settings.ACTION_DATE_SETTINGS)
            p.contains("language") -> Intent(Settings.ACTION_LOCALE_SETTINGS)
            else -> Intent(Settings.ACTION_SETTINGS)
        }
        return start(intent, "Opened ${page.ifBlank { "Settings" }}. Android only lets the user flip that switch themselves.")
    }

    private fun media(action: String): ToolResult {
        val code = when (action.lowercase()) {
            "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev", "back" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        return ToolResult("Sent ${action.lowercase()} to the active media app.")
    }

    private fun battery(): ToolResult {
        val bm = context.getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return ToolResult("Battery $level%${if (charging) ", charging" else ", not charging"}.")
    }

    private fun start(intent: Intent, success: String): ToolResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ToolResult(success.ifEmpty { "Opened." })
        } catch (e: android.content.ActivityNotFoundException) {
            ToolResult.error("no app on this phone handles that")
        }
    }
}

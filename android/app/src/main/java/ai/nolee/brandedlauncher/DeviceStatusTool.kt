package ai.nolee.brandedlauncher

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.bluetooth.BluetoothAdapter
import android.app.ActivityManager
import android.provider.Settings
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Read on demand on the request worker. No shell, identifiers, location, or setting mutations. */
internal object DeviceStatusTool {
    fun read(context: Context): JSONObject {
        val result = JSONObject()
        fun field(name: String, read: () -> Any?) {
            result.put(name, runCatching(read).getOrNull() ?: JSONObject.NULL)
        }
        val now = ZonedDateTime.now()
        field("watch_time") { now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) }
        field("timezone") { now.zone.id }
        field("weekday") { now.dayOfWeek.name }
        field("locale") { Locale.getDefault().toLanguageTag() }
        val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        field("battery_percent") {
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0,100) else null
        }
        field("charging") {
            when (battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                BatteryManager.BATTERY_STATUS_FULL -> "full"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
                else -> null
            }
        }
        field("wifi") {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wifi.isWifiEnabled) "off" else if (wifi.connectionInfo.networkId >= 0) "connected" else "on_not_connected"
        }
        field("bluetooth") { BluetoothAdapter.getDefaultAdapter()?.isEnabled }
        field("brightness_percent") { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) * 100 / 255 }
        field("adaptive_brightness") { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) == 1 }
        field("auto_time") { Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME) == 1 }
        field("auto_timezone") { Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME_ZONE) == 1 }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        for ((name,stream) in listOf("media" to AudioManager.STREAM_MUSIC, "ring" to AudioManager.STREAM_RING,
            "alarm" to AudioManager.STREAM_ALARM, "notification" to AudioManager.STREAM_NOTIFICATION, "call" to AudioManager.STREAM_VOICE_CALL)) {
            field(name + "_percent") { (audio.getStreamVolume(stream)*100 + audio.getStreamMaxVolume(stream)/2) / audio.getStreamMaxVolume(stream).coerceAtLeast(1) }
        }
        field("ringer_mode") { when(audio.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "normal" } }
        val storage = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        field("storage_free_gib") { storage?.let { (it.availableBytes / 10737418L) / 100.0 } }
        field("storage_total_gib") { storage?.let { (it.totalBytes / 10737418L) / 100.0 } }
        field("kiosk") { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE }
        field("spoken_answers") { CloudAi.spokenAnswers(context) }
        field("ai_voice") { CloudAi.voice(context) }
        field("web_search") { CloudAi.webSearch(context) }
        return result
    }
}

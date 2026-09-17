package ai.nolee.brandedlauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** toybox `date` takes the time to set in this one shape. */
private val CLOCK_ARGUMENT = DateTimeFormatter.ofPattern("MMddHHmmyyyy.ss")

data class WifiNet(val ssid: String, val level: Int, val secured: Boolean)

/** The streams the Sound page lists and the voice controls can set. */
enum class SoundStream(val label: String, val stream: Int) {
    Media("MEDIA", AudioManager.STREAM_MUSIC),
    Ring("RING", AudioManager.STREAM_RING),
    Alarm("ALARM", AudioManager.STREAM_ALARM),
    Notification("NOTIFICATION", AudioManager.STREAM_NOTIFICATION),
    Call("CALL", AudioManager.STREAM_VOICE_CALL),
    UiEffects("SYSTEM / UI EFFECTS", AudioManager.STREAM_SYSTEM),
}

/**
 * Live device readings and the system controls the launcher owns. Controls use the public API first and fall
 * back to `su` (as Harness does) when Android refuses; each reports whether the change really happened.
 */
@SuppressLint("MissingPermission")
class DeviceState(private val activity: Activity) {
    private val app = activity.applicationContext
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val stateUri: Uri = Uri.parse("content://io.kungcorp.nolee.launcher.state")
    val bluetooth: BluetoothAdapter? = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()

    var time by mutableStateOf("")
        private set
    var battery by mutableIntStateOf(-1)
        private set
    var batteryTempC by mutableFloatStateOf(Float.NaN)
        private set
    var wifiOn by mutableStateOf(false)
        private set
    var wifiBusy by mutableStateOf(false)
        private set
    var ssid by mutableStateOf<String?>(null)
        private set
    var bluetoothOn by mutableStateOf(false)
        private set
    var bluetoothBusy by mutableStateOf(false)
        private set
    var kioskActive by mutableStateOf(false)
        private set
    var brightness by mutableIntStateOf(128)
        private set
    var adaptive by mutableStateOf(false)
        private set
    /** Android's automatic clock and time zone. With either on, setting one by hand is overwritten again. */
    var autoTime by mutableStateOf(true)
        private set
    var autoTimeZone by mutableStateOf(true)
        private set

    val timeZone: String get() = ZoneId.systemDefault().id

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    battery = if (level >= 0 && scale > 0) Math.round(100f * level / scale) else -1
                    val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                    batteryTempC = if (tenths == Int.MIN_VALUE) Float.NaN else tenths / 10f
                }
                WifiManager.WIFI_STATE_CHANGED_ACTION, WifiManager.NETWORK_STATE_CHANGED_ACTION -> readWifi()
                BluetoothAdapter.ACTION_STATE_CHANGED -> bluetoothOn = bluetooth?.isEnabled == true
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        // Battery is a sticky broadcast: registering returns the current level immediately.
        ContextCompat.registerReceiver(activity, receiver, filter, ContextCompat.RECEIVER_EXPORTED)?.let { receiver.onReceive(activity, it) }
        refresh()
    }

    fun stop() {
        runCatching { activity.unregisterReceiver(receiver) }
    }

    fun refresh() {
        time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        readWifi()
        bluetoothOn = runCatching { bluetooth?.isEnabled == true }.getOrDefault(false)
        kioskActive = activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        brightness = Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
        adaptive = Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1
        autoTime = Settings.Global.getInt(app.contentResolver, Settings.Global.AUTO_TIME, 1) == 1
        autoTimeZone = Settings.Global.getInt(app.contentResolver, Settings.Global.AUTO_TIME_ZONE, 1) == 1
    }

    private fun readWifi() {
        wifiOn = runCatching { wifi.isWifiEnabled }.getOrDefault(false)
        ssid = runCatching { wifi.connectionInfo?.ssid?.trim('"')?.takeUnless { it.isBlank() || it == "<unknown ssid>" } }.getOrNull()
    }

    /** Opens a companion by package; false when it is not installed. Kiosk approval is the Launcher's job. */
    fun launch(packageName: String): Boolean {
        val intent = activity.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return runCatching {
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }.isSuccess
    }

    @Suppress("DEPRECATION")
    suspend fun setWifi(on: Boolean) {
        if (wifiBusy) return
        wifiBusy = true
        withContext(Dispatchers.IO) {
            val accepted = runCatching { wifi.setWifiEnabled(on) }.getOrDefault(false)
            if (!accepted) su("svc wifi ${if (on) "enable" else "disable"}")
        }
        waitFor { runCatching { wifi.isWifiEnabled }.getOrDefault(!on) == on }
        readWifi()
        wifiBusy = false
    }

    suspend fun setBluetooth(on: Boolean) {
        val adapter = bluetooth ?: return
        if (bluetoothBusy) return
        bluetoothBusy = true
        withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            val accepted = runCatching { if (on) adapter.enable() else adapter.disable() }.getOrDefault(false)
            if (!accepted) su("svc bluetooth ${if (on) "enable" else "disable"}")
        }
        waitFor(8_000) { adapter.isEnabled == on }
        bluetoothOn = adapter.isEnabled
        bluetoothBusy = false
    }

    private suspend fun waitFor(timeoutMs: Long = 6_000, done: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!done() && System.currentTimeMillis() < until) delay(200)
    }

    /** Wi-Fi scan; empty unless location permission is granted and location is on (API 28). */
    @Suppress("DEPRECATION")
    suspend fun scanWifi(): List<WifiNet> = withContext(Dispatchers.IO) {
        runCatching { wifi.startScan() }
        delay(900)
        runCatching {
            wifi.scanResults.orEmpty()
                .filter { it.SSID.isNotBlank() }
                .groupBy { it.SSID }
                .map { (name, group) ->
                    val strongest = group.maxBy { it.level }
                    WifiNet(name, strongest.level, strongest.capabilities.contains("WPA") || strongest.capabilities.contains("WEP"))
                }
                .sortedByDescending { it.level }
                .take(12)
        }.getOrDefault(emptyList())
    }

    enum class JoinResult { Connected, WrongPassword, Failed }

    /** Stream levels saved by Mute all, so Restore can put them back after leaving the Sound page. */
    var savedLevels by mutableStateOf<Map<Int, Int>?>(null)

    /**
     * Joins [net] and waits for the outcome. A previously saved configuration for the same SSID is replaced, not
     * reused: reusing it silently retried the old password, so a corrected one was never tried.
     */
    @Suppress("DEPRECATION")
    suspend fun joinWifi(net: WifiNet, password: String?): JoinResult {
        val quoted = "\"${net.ssid}\""
        var authFailed = false
        val watcher = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1) == WifiManager.ERROR_AUTHENTICATING) authFailed = true
            }
        }
        activity.registerReceiver(watcher, IntentFilter(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION))
        try {
            val started = withContext(Dispatchers.IO) {
                runCatching {
                    wifi.configuredNetworks?.filter { it.SSID == quoted }?.forEach { wifi.removeNetwork(it.networkId) }
                    val id = wifi.addNetwork(WifiConfiguration().apply {
                        SSID = quoted
                        if (password.isNullOrBlank()) allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                        else { preSharedKey = "\"$password\""; allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK) }
                    })
                    id >= 0 && wifi.enableNetwork(id, true) && wifi.reconnect()
                }.getOrDefault(false)
            }
            if (!started) return JoinResult.Failed
            val until = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < until) {
                if (authFailed) return JoinResult.WrongPassword
                val info = runCatching { wifi.connectionInfo }.getOrNull()
                if (info?.ssid?.trim('"') == net.ssid && info.supplicantState == android.net.wifi.SupplicantState.COMPLETED) {
                    readWifi()
                    return JoinResult.Connected
                }
                delay(400)
            }
            return JoinResult.Failed
        } finally {
            runCatching { activity.unregisterReceiver(watcher) }
            readWifi()
        }
    }

    fun pairedDevices(): List<BluetoothDevice> =
        runCatching { bluetooth?.bondedDevices.orEmpty().sortedBy { it.name ?: it.address } }.getOrDefault(emptyList())

    fun unpair(device: BluetoothDevice) {
        runCatching { device.javaClass.getMethod("removeBond").invoke(device) }
    }

    /** Writes a system setting with WRITE_SETTINGS, falling back to root. False when neither is available. */
    suspend fun putSystemSetting(key: String, value: Int): Boolean = withContext(Dispatchers.IO) {
        val direct = Settings.System.canWrite(app) && runCatching { Settings.System.putInt(app.contentResolver, key, value) }.getOrDefault(false)
        direct || su("settings put system $key $value")
    }

    suspend fun setBrightness(level: Int): Boolean {
        val next = level.coerceIn(8, 255)
        brightness = next
        return putSystemSetting(Settings.System.SCREEN_BRIGHTNESS, next)
    }

    suspend fun setAdaptive(on: Boolean): Boolean {
        adaptive = on
        return putSystemSetting(Settings.System.SCREEN_BRIGHTNESS_MODE, if (on) 1 else 0).also { if (!it) adaptive = !on }
    }

    /** Writes a global setting. WRITE_SECURE_SETTINGS is granted at setup; root is the fallback. */
    private suspend fun putGlobalSetting(key: String, value: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching { Settings.Global.putInt(app.contentResolver, key, value) }.getOrDefault(false) ||
            su("settings put global $key $value")
    }

    suspend fun setAutoTime(on: Boolean): Boolean {
        autoTime = on
        return putGlobalSetting(Settings.Global.AUTO_TIME, if (on) 1 else 0).also { if (!it) autoTime = !on }
    }

    suspend fun setAutoTimeZone(on: Boolean): Boolean {
        autoTimeZone = on
        return putGlobalSetting(Settings.Global.AUTO_TIME_ZONE, if (on) 1 else 0).also { if (!it) autoTimeZone = !on }
    }

    /**
     * Sets the clock. Android keeps SET_TIME to privileged apps, so this is root's `date`, in the only form the
     * watch's toybox accepts: MMDDhhmmYYYY.ss. `date -s` is not an option it knows.
     */
    suspend fun setClock(at: LocalDateTime): Boolean {
        val ok = withContext(Dispatchers.IO) { su("date ${at.format(CLOCK_ARGUMENT)}") }
        if (ok) refresh()
        return ok
    }

    /**
     * Sets the time zone. SET_TIME_ZONE is privileged too, so root writes the property, which the system picks up
     * live. This process has already cached the old zone, so its default is cleared to make it read the new one.
     */
    suspend fun setTimeZone(id: String): Boolean {
        val ok = withContext(Dispatchers.IO) { su("setprop persist.sys.timezone $id") }
        if (ok) {
            TimeZone.setDefault(null)
            refresh()
        }
        return ok
    }

    fun streamLevel(stream: Int): Int = runCatching { audio.getStreamVolume(stream) }.getOrDefault(0)
    fun streamMax(stream: Int): Int = runCatching { audio.getStreamMaxVolume(stream) }.getOrDefault(15).coerceAtLeast(1)
    /** Lowest level Android accepts for [stream]; the voice call stream is 1, and a 0 there is rejected outright. */
    fun streamMin(stream: Int): Int = runCatching { audio.getStreamMinVolume(stream) }
        .getOrDefault(if (stream == AudioManager.STREAM_VOICE_CALL) 1 else 0)

    /** Bumped on every level change, so an open Sound page follows changes made elsewhere (voice). */
    var soundRevision by mutableIntStateOf(0)
        private set

    fun setStream(stream: Int, level: Int) {
        runCatching { audio.setStreamVolume(stream, level.coerceIn(streamMin(stream), streamMax(stream)), 0) }
        soundRevision++
    }

    /** Moves the brightness shown and the panel itself without writing the setting, for voice's animated sweep. */
    fun previewBrightness(level: Int) {
        brightness = level.coerceIn(8, 255)
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = brightness / 255f }
    }

    /** Hands the panel back to the system brightness setting after [previewBrightness]. */
    fun endBrightnessPreview() {
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = -1f }
    }

    /** Every stream to its minimum. The levels are kept for [restoreLevels]; muting twice keeps the first set. */
    fun muteAll() {
        if (savedLevels == null) savedLevels = SoundStream.entries.associate { it.stream to streamLevel(it.stream) }
        SoundStream.entries.forEach { setStream(it.stream, 0) }
    }

    /** Puts back the levels [muteAll] saved; false when there is nothing to restore. */
    fun restoreLevels(): Boolean {
        val saved = savedLevels ?: return false
        saved.forEach { (stream, level) -> setStream(stream, level) }
        savedLevels = null
        return true
    }

    fun storage(): Pair<Long, Long> {
        val stat = StatFs(Environment.getDataDirectory().path)
        return stat.availableBytes to stat.totalBytes
    }

    val model: String get() = Build.MODEL

    suspend fun exitKiosk(): String? = withContext(Dispatchers.IO) {
        val result = runCatching { app.contentResolver.call(stateUri, "exit_kiosk", null, null) }.getOrNull()
        when {
            result == null -> "Nolee Launcher did not answer."
            result.getBoolean("ok") -> null
            else -> result.getString("reason") ?: "Kiosk exit was refused."
        }
    }

    /** The same root-backed device control used by Nolee Launcher's power menu. */
    suspend fun power(action: String): Boolean = withContext(Dispatchers.IO) {
        require(action == "reboot" || action == "shutdown")
        su("svc power $action", timeoutSeconds = 20)
    }

    private fun su(command: String, timeoutSeconds: Long = 4): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        process.inputStream.close()
        process.waitFor(timeoutSeconds, TimeUnit.SECONDS) && process.exitValue() == 0
    }.getOrDefault(false)
}

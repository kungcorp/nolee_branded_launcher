package ai.nolee.brandedlauncher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The three optical readings, with the vendor measurement ids cross-checked in Harness Vitals.kt. */
enum class VitalMetric(val label: String, val unit: String, val measureType: Int, val sensorType: Int, val valueIndex: Int, val secondIndex: Int? = null) {
    Heart("Heart rate", "BPM", 1, 65599, 1),
    Oxygen("Blood oxygen", "%", 2, 65596, 2),
    Pressure("Blood pressure", "MMHG", 3, 65598, 3, 4),
}

/**
 * Body and device readings for the Vitals pages, measured only while a page's coroutine is running.
 *
 * A real optical reading needs the vendor's measurement mode (`health_measure_type`, `health_measure_status=1`,
 * a wakelock and that metric's own sensor), one metric at a time in 30 s windows. Cancelling a run, by leaving
 * the page or pausing the app, turns the LEDs, listeners and wakelock off in `finally`. Needs
 * `WRITE_SECURE_SETTINGS`:
 *
 *     adb shell pm grant ai.nolee.brandedlauncher android.permission.WRITE_SECURE_SETTINGS
 */
class VitalsMonitor(context: Context) {
    companion object {
        const val WINDOW_MS = 30_000L
    }

    private val app = context.applicationContext
    private val sensors = app.getSystemService(SensorManager::class.java)
    private val power = app.getSystemService(PowerManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val stateUri: Uri = Uri.parse("content://io.kungcorp.nolee.launcher.state")

    data class Reading(
        val heartRate: Int? = null,
        val oxygen: Int? = null,
        val systolic: Int? = null,
        val diastolic: Int? = null,
        val steps: Int? = null,
        val contact: Boolean? = null,
        val socC: Float? = null,
        val boardC: Float? = null,
        val measuring: VitalMetric? = null,
        /** `System.currentTimeMillis()` when the current 30 s window began. */
        val windowStartedAt: Long = 0L,
        val blocked: Boolean = false,
        /** The metric whose last window ran to its end; its result stays on the page until the next run. */
        val completed: VitalMetric? = null,
    ) {
        fun value(metric: VitalMetric): String? = when (metric) {
            VitalMetric.Heart -> heartRate?.toString()
            VitalMetric.Oxygen -> oxygen?.toString()
            VitalMetric.Pressure -> if (systolic != null && diastolic != null) "$systolic/$diastolic" else null
        }
    }

    var reading by mutableStateOf(Reading())
        private set

    /** Overview: steps and temperatures only. The optical sensors stay off. */
    suspend fun overview() = coroutineScope {
        launch { pollThermal() }
        val steps = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                event.values.getOrNull(0)?.toInt()?.let { reading = reading.copy(steps = it) }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val stepSensor = sensors?.getSensorList(Sensor.TYPE_ALL)?.firstOrNull { it.stringType == "android.sensor.step_counter" }
        try {
            if (stepSensor != null) sensors?.registerListener(steps, stepSensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
            delay(Long.MAX_VALUE)
        } finally {
            sensors?.unregisterListener(steps)
        }
    }

    /**
     * Measures [metric] for one 30 s window and stops: the LEDs go off and the result stays on the page. A new run
     * clears that metric's previous value first, so a stale number is never shown as the new reading.
     */
    suspend fun measure(metric: VitalMetric) {
        reading = when (metric) {
            VitalMetric.Heart -> reading.copy(heartRate = null)
            VitalMetric.Oxygen -> reading.copy(oxygen = null)
            VitalMetric.Pressure -> reading.copy(systolic = null, diastolic = null)
        }.copy(completed = null)
        try {
            window(metric)
            reading = reading.copy(completed = metric)
        } finally {
            reading = reading.copy(measuring = null)
        }
    }

    private suspend fun pollThermal() {
        while (true) {
            val thermal = withContext(Dispatchers.IO) { readThermal() }
            if (thermal != null) reading = reading.copy(socC = thermal.first ?: reading.socC, boardC = thermal.second ?: reading.boardC)
            delay(5_000)
        }
    }

    private suspend fun window(metric: VitalMetric) {
        val manager = sensors ?: return delay(WINDOW_MS)
        val sensor = manager.getDefaultSensor(metric.sensorType) ?: return delay(WINDOW_MS)
        // Without BODY_SENSORS, SensorService refuses to enable the heart-rate sensor and logs it, and the page would
        // sit on "measuring" with no reading. Say so instead.
        if (app.checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED ||
            !setMeasurementMode(metric.measureType, on = true)
        ) {
            reading = reading.copy(blocked = true, measuring = null)
            return delay(WINDOW_MS)
        }
        reading = reading.copy(blocked = false, measuring = metric, contact = null, windowStartedAt = System.currentTimeMillis())
        val lock = runCatching { power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nolee-branded-launcher:vitals")?.apply { acquire(WINDOW_MS + 5_000L) } }.getOrNull()
        // A fresh listener per window, unregistered whole, so no instance can keep the LEDs on.
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val contact = (event.values.getOrNull(0)?.toInt() ?: 0) != 0
                reading = reading.copy(contact = contact)
                if (!contact) return
                val first = event.values.getOrNull(metric.valueIndex)?.toInt()?.takeIf { it > 0 } ?: return
                val second = metric.secondIndex?.let { event.values.getOrNull(it)?.toInt() }
                reading = when (metric) {
                    VitalMetric.Heart -> reading.copy(heartRate = first)
                    VitalMetric.Oxygen -> reading.copy(oxygen = first)
                    VitalMetric.Pressure -> if (second != null && second > 0) reading.copy(systolic = first, diastolic = second) else reading
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        try {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
            delay(WINDOW_MS)
        } finally {
            manager.unregisterListener(listener)
            setMeasurementMode(0, on = false)
            runCatching { lock?.let { if (it.isHeld) it.release() } }
        }
    }

    private fun setMeasurementMode(type: Int, on: Boolean): Boolean = runCatching {
        val resolver = app.contentResolver
        if (on && !Settings.Global.putInt(resolver, "health_measure_type", type)) return@runCatching false
        Settings.Global.putInt(resolver, "health_measure_status", if (on) 1 else 0)
    }.getOrDefault(false)

    private fun readThermal(): Pair<Float?, Float?>? = runCatching {
        val result = app.contentResolver.call(stateUri, "thermal", null, null) ?: return null
        if (!result.getBoolean("ok", false)) return null
        Pair(
            if (result.containsKey("soc_c")) result.getFloat("soc_c") else null,
            if (result.containsKey("board_c")) result.getFloat("board_c") else null,
        )
    }.getOrNull()
}

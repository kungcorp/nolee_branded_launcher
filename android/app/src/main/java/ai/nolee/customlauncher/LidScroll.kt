package ai.nolee.customlauncher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Lid up/down scroll through Nolee Launcher's bridge (AGENTS.md "Lid scroll"): Android never delivers the lid's
 * motion as an input event, so the Launcher reads it and fills in a PendingIntent we registered. Registered while
 * resumed only. `step_up` is +1 (next card, scroll down), as in Harness and Neumorphic.
 *
 * Debug builds also accept `ai.nolee.customlauncher.DEBUG_LID_STEP` from the shell, since adb cannot create our
 * PendingIntent: `adb shell am broadcast -a ai.nolee.customlauncher.DEBUG_LID_STEP --es direction step_up`.
 */
class LidScroll(private val context: Context, private val onStep: (Int) -> Unit) {
    private val worker = Executors.newSingleThreadExecutor()
    private val action = "${context.packageName}.LID_STEP"
    private val debugAction = "${context.packageName}.DEBUG_LID_STEP"
    private val stateUri: Uri = Uri.parse("content://io.kungcorp.nolee.launcher.state")
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == action && intent.getIntExtra("schema", -1) != 1) return
            when (intent?.getStringExtra("direction")) {
                "step_up" -> onStep(1)
                "step_down" -> onStep(-1)
            }
        }
    }

    fun start() {
        if (registered) return
        registered = true
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        if (BuildConfig.DEBUG) {
            // Shell and system only: DUMP is held by adb, not by other installed apps.
            ContextCompat.registerReceiver(context, debugReceiver, IntentFilter(debugAction), "android.permission.DUMP", null, ContextCompat.RECEIVER_EXPORTED)
        }
        worker.execute {
            val callback = PendingIntent.getBroadcast(
                context, 0, Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            runCatching { context.contentResolver.call(stateUri, "register_lid_scroll", null, Bundle().apply { putParcelable("callback", callback) }) }
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { context.unregisterReceiver(receiver) }
        if (BuildConfig.DEBUG) runCatching { context.unregisterReceiver(debugReceiver) }
        // One FIFO worker keeps a rapid stop/start from unregistering the newer subscription.
        worker.execute { runCatching { context.contentResolver.call(stateUri, "unregister_lid_scroll", null, null) } }
    }

    fun destroy() {
        stop()
        worker.shutdown()
    }

    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) = receiver.onReceive(c, intent)
    }
}

/** The scrolling list on screen, if any, so a lid step can scroll it. Set by [PageBody]. */
object LidTargets {
    var scroll: ((Int) -> Unit)? = null
}

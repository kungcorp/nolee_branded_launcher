package ai.nolee.brandedlauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Zones the owner is likely to want, and what to say for each. The picker and the voice grammar read the same
 * catalogue, so they cannot drift apart; `VoiceGrammarTest` checks that every spoken name runs.
 */
val TIME_ZONES = linkedMapOf(
    "UTC" to "u t c",
    "Europe/London" to "london",
    "Europe/Paris" to "paris",
    "Europe/Berlin" to "berlin",
    "America/New_York" to "new york",
    "America/Chicago" to "chicago",
    "America/Denver" to "denver",
    "America/Los_Angeles" to "los angeles",
    "Asia/Dubai" to "dubai",
    "Asia/Kolkata" to "kolkata",
    "Asia/Bangkok" to "bangkok",
    "Asia/Singapore" to "singapore",
    "Asia/Hong_Kong" to "hong kong",
    "Asia/Shanghai" to "shanghai",
    "Asia/Taipei" to "taipei",
    "Asia/Tokyo" to "tokyo",
    "Asia/Seoul" to "seoul",
    "Australia/Sydney" to "sydney",
    "Pacific/Auckland" to "auckland",
)

private val MONTH = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
private val SET_AT = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)

/** "UTC+08:00", as the zone list shows it. */
private fun offsetOf(id: String): String = runCatching {
    val offset = ZoneId.of(id).rules.getOffset(Instant.now()).id
    if (offset == "Z") "UTC+00:00" else "UTC$offset"
}.getOrDefault("UTC")

/** Seconds from UTC, so the list runs west to east rather than by how the offset happens to spell. */
private fun offsetSeconds(id: String): Int =
    runCatching { ZoneId.of(id).rules.getOffset(Instant.now()).totalSeconds }.getOrDefault(0)

/**
 * Date & time. Android keeps setting the clock and the zone to privileged apps, so both go through root, and
 * Android's own automatic time has to be off first or the network puts its answer straight back.
 */
@Composable
fun DateTimePage(stage: Stage, device: DeviceState) {
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf(LocalDateTime.now().withSecond(0).withNano(0)) }
    var status by remember { mutableStateOf<String?>(null) }
    var pickingZone by remember { mutableStateOf(false) }

    PageTitle(stage, "Date & time", if (pickingZone) "TIME ZONE" else "SYSTEM")
    PageBody(stage) {
        if (pickingZone) {
            SectionLabel(stage, "TIME ZONE")
            (TIME_ZONES.keys + device.timeZone).distinct().sortedBy { offsetSeconds(it) }.forEach { id ->
                val here = id == device.timeZone
                SettingRow(
                    stage,
                    id.substringAfterLast('/').replace('_', ' '),
                    "${id.substringBefore('/')} · ${offsetOf(id)}",
                    value = if (here) "●" else "›",
                    onClick = {
                        scope.launch {
                            status = if (device.setTimeZone(id)) "Time zone set to $id." else "Root refused the time zone."
                            target = LocalDateTime.now().withSecond(0).withNano(0)
                            pickingZone = false
                        }
                    },
                )
            }
            Spacer(Modifier.height(stage.dp(12f)))
            ActionButton(stage, "BACK", filled = false) { pickingZone = false }
            Spacer(Modifier.height(stage.dp(12f)))
            return@PageBody
        }

        ToggleRow(stage, "Automatic", if (device.autoTime) "Clock set by the network" else "Clock set by hand", device.autoTime) {
            scope.launch {
                val on = !device.autoTime
                status = if (device.setAutoTime(on)) null else "Android refused the change."
                if (!on) target = LocalDateTime.now().withSecond(0).withNano(0)
            }
        }
        ToggleRow(stage, "Automatic zone", if (device.autoTimeZone) "Zone from the network" else "Zone set by hand", device.autoTimeZone) {
            scope.launch { status = if (device.setAutoTimeZone(!device.autoTimeZone)) null else "Android refused the change." }
        }
        SettingRow(
            stage,
            "Time zone",
            "${device.timeZone.replace('_', ' ')} · ${offsetOf(device.timeZone)}",
            value = "›",
            onClick = { pickingZone = true },
        )

        if (device.autoTime) {
            Notice(stage, "The network keeps this clock. Turn Automatic off to set the date and time by hand.")
        } else {
            SectionLabel(stage, "SET THE CLOCK")
            StepperRow(stage, "Year", target.year.toString()) { target = target.plusYears(it.toLong()) }
            StepperRow(stage, "Month", target.format(MONTH).uppercase(Locale.ENGLISH)) { target = target.plusMonths(it.toLong()) }
            StepperRow(stage, "Day", target.dayOfMonth.toString().padStart(2, '0')) { target = target.plusDays(it.toLong()) }
            StepperRow(stage, "Hour", target.hour.toString().padStart(2, '0')) { target = target.plusHours(it.toLong()) }
            StepperRow(stage, "Minute", target.minute.toString().padStart(2, '0')) { target = target.plusMinutes(it.toLong()) }
            Spacer(Modifier.height(stage.dp(12f)))
            Row(horizontalArrangement = Arrangement.spacedBy(stage.dp(10f))) {
                ActionButton(stage, "SET CLOCK", filled = true) {
                    scope.launch {
                        status = if (device.setClock(target)) "Clock set to ${target.format(SET_AT)}." else "Root refused to set the clock."
                    }
                }
                ActionButton(stage, "NOW", filled = false) { target = LocalDateTime.now().withSecond(0).withNano(0) }
            }
        }
        status?.let { Notice(stage, it) }
        Spacer(Modifier.height(stage.dp(12f)))
    }
}

/** A row whose value is changed a step at a time, for numbers too fiddly to type on this screen. */
@Composable
fun StepperRow(stage: Stage, label: String, value: String, onStep: (Int) -> Unit) {
    SettingRow(stage, label) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(stage, "−") { onStep(-1) }
            BasicText(
                value,
                Modifier.width(stage.dp(58f)),
                style = stage.text(17f, Palette.Ink, 22f).copy(textAlign = TextAlign.Center),
            )
            StepButton(stage, "+") { onStep(1) }
        }
    }
}

@Composable
private fun StepButton(stage: Stage, glyph: String, onTap: () -> Unit) {
    Box(
        Modifier
            .border(stage.dp(1f), Palette.Mint)
            .background(Color(0x1983F5D0))
            .clickable(onClick = onTap)
            .padding(horizontal = stage.dp(11f), vertical = stage.dp(7f)),
    ) {
        BasicText(glyph, style = stage.text(15f, Palette.Mint, 19f))
    }
}

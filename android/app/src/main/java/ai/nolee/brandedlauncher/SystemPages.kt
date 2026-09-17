package ai.nolee.brandedlauncher

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val centred = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

internal fun Stage.text(size: Float, color: Color, lineHeight: Float = size * 1.25f, spacing: Float = 0f) =
    TextStyle(fontFamily = Spline, fontSize = sp(size), color = color, lineHeight = sp(lineHeight), letterSpacing = sp(spacing), lineHeightStyle = centred)

enum class SystemEntry(val label: String, val glyph: Glyph, val page: Page) {
    Profile("Profile", Glyph.Profile, Page.Profile),
    DateTime("Date & time", Glyph.Calendar, Page.DateTime),
    Wifi("Wi-Fi", Glyph.Wifi, Page.Wifi),
    Bluetooth("Bluetooth", Glyph.Bluetooth, Page.Bluetooth),
    Display("Display", Glyph.Display, Page.Display),
    Sound("Sound", Glyph.Sound, Page.Sound),
    Launcher("Launcher", Glyph.Launcher, Page.Launcher),
    Shutdown("Shutdown", Glyph.Power, Page.Power),
}

@Composable
fun PageTitle(stage: Stage, title: String, code: String) {
    Box(Modifier.at(stage, 39f, 78f, 332f, 27.6f)) {
        BasicText(title, Modifier.align(Alignment.CenterStart).arrive(stage, from = -22f), style = stage.text(23f, Palette.Ink, 27.6f, -1f))
        BasicText(code, Modifier.align(Alignment.CenterEnd).arrive(stage, from = 22f), style = stage.text(13f, Palette.Mint))
    }
}

/** Scrolling body below the title; it stops 79 px above the bottom so rows stay inside the rounded lens. */
@Composable
fun PageBody(stage: Stage, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Lid scroll moves the list one row (58 px) per step.
    DisposableEffect(scroll) {
        val handler: (Int) -> Unit = { step -> scope.launch { scroll.animateScrollBy(stage.px(58f) * step) } }
        LidTargets.scroll = handler
        onDispose { if (LidTargets.scroll === handler) LidTargets.scroll = null }
    }
    Column(Modifier.at(stage, 39f, 120.6f, 332f, 300f).clipToBounds().verticalScroll(scroll), content = content)
}

@Composable
fun SectionLabel(stage: Stage, label: String) {
    BasicText(label, Modifier.arrive(stage).padding(top = stage.dp(13f), bottom = stage.dp(7f)), style = stage.text(13f, Palette.Count, spacing = 1f))
}

@Composable
fun SettingRow(stage: Stage, title: String, detail: String? = null, value: String = "↗", onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .arrive(stage)
            .fillMaxWidth()
            .heightIn(min = stage.dp(58f))
            .background(if (pressed) Color(0x1A83F5D0) else Palette.RowCard)
            .drawBehind { drawRect(Palette.Hair, Offset(0f, size.height - stage.px(1f)), Size(size.width, stage.px(1f))) }
            .then(if (onClick != null) Modifier.clickable(interaction, indication = null, onClick = onClick) else Modifier)
            .padding(stage.dp(9f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = stage.text(15f, Palette.Ink, 19f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Spacer(Modifier.height(stage.dp(5f)))
                BasicText(detail, style = stage.text(13f, Palette.RowDetail, 16f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) trailing() else BasicText(value, Modifier.padding(start = stage.dp(8f)), style = stage.text(13f, Palette.Mint))
    }
}

@Composable
fun ToggleRow(stage: Stage, title: String, detail: String?, on: Boolean, busy: Boolean = false, onToggle: () -> Unit) {
    SettingRow(stage, title, detail, onClick = { if (!busy) onToggle() }) {
        BasicText(
            if (busy) "…" else if (on) "ON" else "OFF",
            Modifier.border(stage.dp(1f), Palette.Mint).background(Color(0x1983F5D0)).padding(stage.dp(8f)),
            style = stage.text(13f, Palette.Mint, 16f),
        )
    }
}

/** Harness's segmented level control (InstrumentLevelRow), in mint. Tap or drag anywhere along the bar. */
@Composable
fun LevelRow(stage: Stage, label: String, value: Int, steps: Int, onChange: (Int) -> Unit) {
    var width by remember { mutableIntStateOf(1) }
    fun pick(x: Float) = onChange(((x / width.coerceAtLeast(1)).coerceIn(0f, 1f) * steps).roundToInt().coerceIn(0, steps))
    val shape = RoundedCornerShape(stage.dp(5f))
    Column(
        Modifier
            .arrive(stage)
            .fillMaxWidth()
            .padding(bottom = stage.dp(9f))
            .border(stage.dp(1f), Color(0x3383F5D0), shape)
            .background(Color(0xE8060B08), shape)
            .padding(horizontal = stage.dp(14f), vertical = stage.dp(10f)),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(label, Modifier.weight(1f), style = stage.text(13f, Palette.Ink, spacing = .7f))
            BasicText("${(value * 100f / steps.coerceAtLeast(1)).roundToInt()}%", style = stage.text(13f, Palette.Mint))
        }
        Spacer(Modifier.height(stage.dp(9f)))
        Row(
            Modifier
                .fillMaxWidth()
                .height(stage.dp(20f))
                .onSizeChanged { width = it.width }
                .pointerInput(steps) { detectTapGestures { pick(it.x) } }
                .pointerInput(steps) { detectHorizontalDragGestures { change, _ -> change.consume(); pick(change.position.x) } },
            horizontalArrangement = Arrangement.spacedBy(stage.dp(2.5f)),
        ) {
            val lit = (value.coerceIn(0, steps) * 18f / steps.coerceAtLeast(1)).roundToInt()
            repeat(18) { index ->
                Box(Modifier.weight(1f).fillMaxHeight().background(if (index < lit) Palette.Mint else Color(0xFF1C2B24)))
            }
        }
    }
}

@Composable
fun Notice(stage: Stage, text: String) {
    BasicText(text, Modifier.arrive(stage).padding(horizontal = stage.dp(3f), vertical = stage.dp(12f)), style = stage.text(13f, Palette.Notice, 19.5f))
}

@Composable
internal fun ActionButton(stage: Stage, label: String, filled: Boolean, onClick: () -> Unit) {
    BasicText(
        label,
        Modifier
            .arrive(stage)
            .border(stage.dp(1f), Palette.Mint)
            .background(if (filled) Palette.Mint else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = stage.dp(13f), vertical = stage.dp(10f)),
        style = stage.text(13f, if (filled) Palette.Ground else Palette.Mint, spacing = 1f),
    )
}

/** System and its sub-pages. Every value is read from, and every control acts on, the device. */
@Composable
fun SystemPages(stage: Stage, page: Page, device: DeviceState, drum: DrumState, profile: Profile, profileUi: ProfileUi, go: (Page) -> Unit, exitKiosk: () -> Unit) {
    when (page) {
        Page.System -> SystemMenu(stage, device, drum, profile, go)
        Page.Profile -> ProfilePage(stage, profile, profileUi)
        Page.DateTime -> DateTimePage(stage, device)
        Page.Wifi -> WifiPage(stage, device)
        Page.Bluetooth -> BluetoothPage(stage, device)
        Page.Display -> DisplayPage(stage, device)
        Page.Sound -> SoundPage(stage, device)
        Page.Launcher -> {
            PageTitle(stage, "Launcher", "SYSTEM")
            PageBody(stage) {
                SettingRow(stage, "Kiosk mode", value = if (device.kioskActive) "ACTIVE" else "OFF")
                if (device.kioskActive) SettingRow(stage, "Leave kiosk", "Returns to Nolee Launcher", onClick = exitKiosk)
                Notice(stage, "Choose the primary app and approved companions in Nolee Launcher’s Kiosk settings.")
            }
        }
        else -> Unit
    }
}

/** System as a rolling drum of full-size cards, the same size as the main menu's. */
@Composable
private fun SystemMenu(stage: Stage, device: DeviceState, drum: DrumState, profile: Profile, go: (Page) -> Unit) {
    PageTitle(stage, "System", "CONTROL / ${(drum.selectedIndex + 1).toString().padStart(2, '0')}")
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val items = SystemEntry.entries.map { entry ->
        val subtitle = when (entry) {
            SystemEntry.Profile -> profile.name.ifBlank { "NOT SET" }.uppercase()
            SystemEntry.DateTime -> "${device.time} · ${device.timeZone.substringAfterLast('/').replace('_', ' ').uppercase()}"
            SystemEntry.Wifi -> if (!device.wifiOn) "RADIO OFF" else device.ssid?.uppercase() ?: "ON · NOT CONNECTED"
            SystemEntry.Bluetooth -> if (device.bluetoothOn) "ON" else "RADIO OFF"
            SystemEntry.Display -> if (device.adaptive) "ADAPTIVE" else "BRIGHTNESS ${device.brightness * 100 / 255}%"
            SystemEntry.Sound -> "MEDIA ${audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)}%"
            SystemEntry.Launcher -> if (device.kioskActive) "KIOSK ACTIVE" else "KIOSK OFF"
            SystemEntry.Shutdown -> "RESTART · SHUT DOWN"
        }
        DrumItem(entry.label, subtitle, entry.glyph)
    }
    Drum(stage, drum, items, DrumGeometry.Tall, 39f, 118f, pageClock(), riseDelay = 80, emphasizeSelected = true) {
        go(SystemEntry.entries[it].page)
    }
}

@Composable
private fun WifiPage(stage: Stage, device: DeviceState) {
    val scope = rememberCoroutineScope()
    var nets by remember { mutableStateOf(emptyList<WifiNet>()) }
    var joining by remember { mutableStateOf<WifiNet?>(null) }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    PageTitle(stage, "Wi-Fi", "SYSTEM")

    // Scans only while this page is showing.
    LaunchedEffect(device.wifiOn) {
        while (device.wifiOn) {
            nets = device.scanWifi()
            delay(5_000)
        }
        nets = emptyList()
    }

    val target = joining
    PageBody(stage) {
        if (target != null) {
            SectionLabel(stage, "JOIN NETWORK")
            BasicText(target.ssid, style = stage.text(17f, Palette.Ink))
            Spacer(Modifier.height(stage.dp(12f)))
            if (target.secured) {
                BasicTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    textStyle = stage.text(14f, Palette.Ink),
                    cursorBrush = SolidColor(Palette.Mint),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().border(stage.dp(1f), Palette.Hair).background(Palette.RowCard).padding(stage.dp(12f)),
                )
            } else {
                BasicText("Open network", style = stage.text(13f, Palette.Notice))
            }
            status?.let { Notice(stage, it) }
            Spacer(Modifier.height(stage.dp(12f)))
            Row(horizontalArrangement = Arrangement.spacedBy(stage.dp(10f))) {
                ActionButton(stage, "CONNECT", filled = true) {
                    status = "Connecting…"
                    scope.launch {
                        when (device.joinWifi(target, password.takeIf { target.secured })) {
                            DeviceState.JoinResult.Connected -> {
                                status = "Connected to ${target.ssid}."
                                delay(1_200); joining = null; password = ""; status = null
                            }
                            DeviceState.JoinResult.WrongPassword -> status = "Wrong password. Check it and try again."
                            DeviceState.JoinResult.Failed -> status = "Could not connect. The network may be out of range."
                        }
                    }
                }
                ActionButton(stage, "CANCEL", filled = false) { joining = null; password = ""; status = null }
            }
            return@PageBody
        }
        ToggleRow(stage, "Wi-Fi", if (!device.wifiOn) "Radio off" else device.ssid ?: "Not connected", device.wifiOn, device.wifiBusy) {
            scope.launch { device.setWifi(!device.wifiOn) }
        }
        if (!device.wifiOn) return@PageBody
        SectionLabel(stage, "NETWORKS")
        if (nets.isEmpty()) Notice(stage, "Scanning… Results need location permission and location turned on.")
        nets.forEach { net ->
            val strength = when { net.level >= -55 -> "Strong"; net.level >= -70 -> "Good"; else -> "Weak" }
            SettingRow(
                stage, net.ssid,
                when { device.ssid == net.ssid -> "Connected"; net.secured -> "Secured · $strength"; else -> "Open · $strength" },
                value = if (device.ssid == net.ssid) "●" else "›",
                onClick = { joining = net; password = ""; status = null },
            )
        }
    }
}

@Composable
private fun BluetoothPage(stage: Stage, device: DeviceState) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var paired by remember { mutableStateOf(device.pairedDevices()) }
    var found by remember { mutableStateOf(emptyList<BluetoothDevice>()) }
    var scanning by remember { mutableStateOf(false) }
    PageTitle(stage, "Bluetooth", "SYSTEM")

    // Discovery and its receiver live only as long as this page.
    DisposableEffect(device.bluetoothOn) {
        paired = device.pairedDevices()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        @Suppress("DEPRECATION")
                        val found1: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        if (found1 != null && found.none { it.address == found1.address }) found = found + found1
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning = false
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> paired = device.pairedDevices()
                }
            }
        }
        runCatching {
            context.registerReceiver(receiver, IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            })
        }
        onDispose {
            @Suppress("MissingPermission")
            runCatching { device.bluetooth?.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    PageBody(stage) {
        @Suppress("MissingPermission")
        ToggleRow(stage, "Bluetooth", if (device.bluetoothOn) device.bluetooth?.name ?: "On" else "Radio off", device.bluetoothOn, device.bluetoothBusy) {
            scope.launch { device.setBluetooth(!device.bluetoothOn) }
        }
        if (!device.bluetoothOn) return@PageBody
        SectionLabel(stage, "PAIRED")
        if (paired.isEmpty()) Notice(stage, "Nothing paired.")
        @Suppress("MissingPermission")
        paired.forEach { d -> SettingRow(stage, d.name ?: "Unknown device", d.address, value = "UNPAIR", onClick = { device.unpair(d) }) }
        SectionLabel(stage, "NEARBY")
        @Suppress("MissingPermission")
        found.filter { f -> paired.none { it.address == f.address } }.forEach { d ->
            SettingRow(stage, d.name ?: "Unknown device", d.address, value = "PAIR", onClick = {
                runCatching { device.bluetooth?.cancelDiscovery() }
                runCatching { d.createBond() }
            })
        }
        Spacer(Modifier.height(stage.dp(10f)))
        ActionButton(stage, if (scanning) "SCANNING…" else "SCAN FOR DEVICES", filled = true) {
            if (!scanning) {
                found = emptyList()
                @Suppress("MissingPermission")
                scanning = runCatching { device.bluetooth?.cancelDiscovery(); device.bluetooth?.startDiscovery() == true }.getOrDefault(false)
            }
        }
        Spacer(Modifier.height(stage.dp(12f)))
    }
}

@Composable
private fun DisplayPage(stage: Stage, device: DeviceState) {
    val scope = rememberCoroutineScope()
    var level by remember { mutableIntStateOf(device.brightness) }
    var refused by remember { mutableStateOf(false) }
    // Follow brightness changed elsewhere (voice's sweep), so the bar moves with the panel.
    LaunchedEffect(device.brightness) { if (level != device.brightness) level = device.brightness }
    // The bar follows the finger immediately; the system setting is written at most every 80 ms.
    LaunchedEffect(Unit) {
        snapshotFlow { level }.drop(1).collectLatest { delay(80); refused = !device.setBrightness(it) }
    }
    PageTitle(stage, "Display", "SYSTEM")
    PageBody(stage) {
        SectionLabel(stage, "BRIGHTNESS / PANEL")
        LevelRow(stage, "LEVEL", level, 255) { level = it.coerceAtLeast(8) }
        ToggleRow(stage, "Adaptive", "Ambient response", device.adaptive) {
            scope.launch { refused = !device.setAdaptive(!device.adaptive) }
        }
        if (refused) Notice(stage, "Android refused the change. Grant WRITE_SETTINGS or root to this app.")
    }
}

@Composable
private fun SoundPage(stage: Stage, device: DeviceState) {
    // Re-read whenever any level changes, including voice's animated sweeps while this page is showing.
    val levels = remember(device.soundRevision) { SoundStream.entries.map { device.streamLevel(it.stream) } }
    val saved = device.savedLevels
    PageTitle(stage, "Sound", "SYSTEM")
    PageBody(stage) {
        SectionLabel(stage, "ALL SOUNDS")
        // Mute sends every stream to its minimum (the call stream cannot go below 1). The saved levels stay
        // available, even after leaving the page, until they are restored or a level is changed by hand.
        ActionButton(stage, if (saved != null) "RESTORE LEVELS" else "MUTE ALL", filled = true) {
            if (saved != null) device.restoreLevels() else device.muteAll()
        }
        SectionLabel(stage, "OUTPUT LEVELS")
        SoundStream.entries.forEachIndexed { index, sound ->
            LevelRow(stage, sound.label, levels[index], device.streamMax(sound.stream)) { next ->
                device.setStream(sound.stream, next)
                device.savedLevels = null
            }
        }
    }
}

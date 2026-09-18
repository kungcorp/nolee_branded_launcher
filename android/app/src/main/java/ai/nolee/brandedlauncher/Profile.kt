package ai.nolee.brandedlauncher

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue

/** What the owner can tell the watch about themselves. [max] keeps each value short enough to show on its card. */
enum class ProfileField(val label: String, val hint: String, val max: Int, val glyph: Glyph, val numeric: Boolean = false, val multiline: Boolean = false) {
    Name("Name", "What the watch should call you. Home greets you with it.", 20, Glyph.Profile),
    Age("Age", "In years.", 3, Glyph.Time, numeric = true),
    Occupation("Occupation", "What you do.", 40, Glyph.Work),
    City("City", "Where you are based.", 40, Glyph.Pin),
    Country("Country", "Your country or region.", 40, Glyph.Pin),
    About("About", "Anything else worth knowing about you.", 160, Glyph.Note, multiline = true),
}

/**
 * The owner's profile, kept in this app's private storage on the watch. Home greets them by [name]; the rest is
 * sent as bounded context with Cloud AI persona questions when nonempty.
 */
@Stable
class Profile(context: Context) {
    private val prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)
    private val values = ProfileField.entries.associateWith { mutableStateOf(prefs.getString(it.name, null).orEmpty()) }

    // Provisioning can update the profile while Home is already open.
    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        ProfileField.entries.firstOrNull { it.name == key }?.let { field ->
            values.getValue(field).value = prefs.getString(field.name, null).orEmpty()
        }
    }
    init { prefs.registerOnSharedPreferenceChangeListener(listener) }

    fun close() { prefs.unregisterOnSharedPreferenceChangeListener(listener) }

    operator fun get(field: ProfileField): String = values.getValue(field).value

    operator fun set(field: ProfileField, value: String) {
        val clean = value.trim().take(field.max)
        values.getValue(field).value = clean
        prefs.edit().putString(field.name, clean).apply()
    }

    val name: String get() = this[ProfileField.Name]
}

/** Profile's on-screen state. MainActivity holds it so the lid and the hardware keys can drive the drum. */
@Stable
class ProfileUi {
    val drum = DrumState(ProfileField.entries.size)
    var editing by mutableStateOf<ProfileField?>(null)
}

/**
 * Profile: the fields roll past as full-size cards, the same drum System uses. Opening one swaps the drum for a
 * single field at the top of the page, clear of the keyboard.
 */
@Composable
fun ProfilePage(stage: Stage, profile: Profile, ui: ProfileUi) {
    val field = ui.editing
    if (field == null) {
        PageTitle(stage, "Profile", "ABOUT / ${(ui.drum.selectedIndex + 1).toString().padStart(2, '0')}")
        val items = ProfileField.entries.map { DrumItem(it.label, profile[it].ifBlank { "Not set" }.uppercase(), it.glyph) }
        Drum(stage, ui.drum, items, DrumGeometry.Tall, 39f, 118f, pageClock(), riseDelay = 80) { ui.editing = ProfileField.entries[it] }
        return
    }

    val keyboard = LocalSoftwareKeyboardController.current
    var draft by remember(field) { mutableStateOf(TextFieldValue(profile[field], TextRange(profile[field].length))) }
    fun close(save: Boolean) {
        if (save) profile[field] = draft.text
        keyboard?.hide()
        ui.editing = null
    }

    PageTitle(stage, "Profile", field.label.uppercase())
    PageBody(stage) {
        SectionLabel(stage, field.label.uppercase())
        val focus = remember(field) { FocusRequester() }
        BasicTextField(
            value = draft,
            onValueChange = { next ->
                val text = (if (field.numeric) next.text.filter(Char::isDigit) else next.text).take(field.max)
                draft = if (text == next.text) next else next.copy(text = text, selection = TextRange(text.length))
            },
            singleLine = !field.multiline,
            maxLines = if (field.multiline) 4 else 1,
            textStyle = stage.text(17f, Palette.Ink, 23f),
            cursorBrush = SolidColor(Palette.Mint),
            keyboardOptions = KeyboardOptions(
                capitalization = when {
                    field.numeric -> KeyboardCapitalization.None
                    field.multiline -> KeyboardCapitalization.Sentences
                    else -> KeyboardCapitalization.Words
                },
                keyboardType = if (field.numeric) KeyboardType.Number else KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { close(save = true) }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .border(stage.dp(1f), Palette.Hair)
                .background(Palette.RowCard)
                .padding(stage.dp(12f)),
        )
        LaunchedEffect(focus) { focus.requestFocus() }
        BasicText(field.hint, Modifier.padding(top = stage.dp(8f)), style = stage.text(13f, Palette.Notice, 18f))
        Spacer(Modifier.height(stage.dp(12f)))
        Row(horizontalArrangement = Arrangement.spacedBy(stage.dp(10f))) {
            ActionButton(stage, "SAVE", filled = true) { close(save = true) }
            ActionButton(stage, "CANCEL", filled = false) { close(save = false) }
        }
    }
}

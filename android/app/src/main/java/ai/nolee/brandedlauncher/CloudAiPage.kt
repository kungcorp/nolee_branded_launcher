package ai.nolee.brandedlauncher

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject

/** App-local preferences; entitlement and the key remain owned by the stock Launcher. */
@Composable
fun CloudAiPage(stage: Stage, openVoice: () -> Unit, start: () -> Unit) {
    val context = LocalContext.current
    val client = remember { CloudAi(context.applicationContext, { emptyList() }, {}, {}) }
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var spoken by remember { mutableStateOf(CloudAi.spokenAnswers(context)) }
    var web by remember { mutableStateOf(CloudAi.webSearch(context)) }
    val voice = CloudAi.voice(context)
    fun refresh() {
        loading = true
        error = null
        client.refreshSettings { result ->
            loading = false
            result.onSuccess { status = it }.onFailure { error = it.message; status = null }
        }
    }
    DisposableEffect(client) {
        refresh()
        onDispose { client.destroy() }
    }
    PageTitle(stage, "Nolee AI", "CLOUD")
    PageBody(stage) {
        SettingRow(stage, "Start Nolee AI", "Open your cloud companion", onClick = start)
        val state = status?.optString("credential_status").orEmpty()
        SettingRow(stage, "Status", value = when {
            loading -> "CHECKING"
            error != null -> "UNAVAILABLE"
            state.isBlank() -> "UNKNOWN"
            else -> state.uppercase()
        })
        error?.let { Notice(stage, it) }
        if (state == "pending" || state == "expired" || state == "revoked") {
            Notice(stage, "Manage activation in Nolee Launcher → System → Applications → Nolee AI.")
        }
        status?.optString("valid_until")?.takeIf { it.isNotBlank() && it != "null" }?.let {
            SettingRow(stage, "Valid until", value = it.substringBefore('T'))
        }
        SectionLabel(stage, "SHARED ALLOWANCE")
        val usage = status?.optJSONObject("usage")
        fun counter(used: String, limit: String): String = if (usage?.has(used) == true && usage.has(limit))
            "${usage.optInt(used)} / ${usage.optInt(limit)}" else "—"
        SettingRow(stage, "Today", "Questions used", value = counter("daily_requests_used", "daily_requests_limit"))
        SettingRow(stage, "This month", "Questions used", value = counter("requests_used", "requests_limit"))
        usage?.optString("period_end")?.takeIf { it.isNotBlank() && it != "null" }?.let {
            SettingRow(stage, "Period ends", value = it.substringBefore('T'))
        }
        SettingRow(stage, "Refresh status & usage", value = if (loading) "…" else "↻",
            onClick = if (loading) null else ({ refresh() }))
        SectionLabel(stage, "ANSWERS")
        ToggleRow(stage, "Web search", null, web) {
            web = !web
            CloudAi.setWebSearch(context, web)
        }
        Notice(stage, "Allow AI to search the web when needed. Device commands, spoken-answer controls and profile updates work with search on or off.")
        ToggleRow(stage, "Spoken answers", null, spoken) {
            spoken = !spoken
            CloudAi.setSpokenAnswers(context, spoken)
        }
        Notice(stage, "Turn this off for text only responses.")
        SettingRow(stage, "Voice", value = "$voice ›", onClick = openVoice)
        Notice(stage, "Preferences apply to this app. Usage is shared with Nolee Launcher. Swipe left/right on the ball to change views; up for top, down for front.")
    }
}

@Composable
fun CloudAiVoicePage(stage: Stage, back: () -> Unit) {
    val context = LocalContext.current
    var voice by remember { mutableStateOf(CloudAi.voice(context)) }
    PageTitle(stage, "Voice", "NOLEE AI")
    PageBody(stage) {
        SettingRow(stage, "Back to Nolee AI", value = "‹", onClick = back)
        CloudAi.VOICES.forEach { name ->
            SettingRow(stage, name, value = if(voice==name) "✓" else "", onClick = {
                voice=CloudAi.setVoice(context,name)
            })
        }
        Notice(stage, "Applies to the next spoken answer in this app.")
    }
}

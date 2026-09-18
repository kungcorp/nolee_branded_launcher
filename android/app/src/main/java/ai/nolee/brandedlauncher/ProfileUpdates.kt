package ai.nolee.brandedlauncher

/** An allowlisted field patch, never a file path or an instruction to execute. */
object ProfileUpdates {
    fun parse(raw: Map<String, String>): Map<ProfileField, String>? {
        if (raw.isEmpty() || raw.size > ProfileField.entries.size) return null
        val result = linkedMapOf<ProfileField, String>()
        for ((key, value) in raw) {
            val field = ProfileField.entries.firstOrNull { it.name.lowercase() == key } ?: return null
            val clean = value.trim()
            if (clean.length > field.max || clean.any { it.code < 32 && !(field.multiline && it == '\n') }) return null
            if (field.numeric && clean.isNotEmpty() && (!clean.matches(Regex("[0-9]{1,3}")) || clean.toInt() !in 0..150)) return null
            result[field] = clean
        }
        return result
    }
}

/** Conversation-only commands must not trigger the device-control navigation handoff. */
fun VoiceCommand.needsDeviceNavigation(): Boolean = when (this) {
    is VoiceCommand.Transcript, is VoiceCommand.SpokenAnswers, is VoiceCommand.UpdateProfile,
    is VoiceCommand.AiVolume, VoiceCommand.StartAi -> false
    else -> true
}

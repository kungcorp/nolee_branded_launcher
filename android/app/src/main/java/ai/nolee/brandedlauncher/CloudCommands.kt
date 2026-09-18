package ai.nolee.brandedlauncher

/** Closed command catalogue: model output never becomes shell, an Intent, or free-form code. */
object CloudCommands {
    private val commands: Map<String, VoiceCommand> by lazy {
        VoiceGrammar.phrases.mapNotNull { phrase -> VoiceGrammar.parse(phrase)?.let { phrase to it } }
            .filter { (_, command) -> command !is VoiceCommand.Brightness && command !is VoiceCommand.Volume }
            .distinctBy { it.second }.toMap() + buildMap {
                put("show transcript", VoiceCommand.Transcript(true))
                put("hide transcript", VoiceCommand.Transcript(false))
                put("mute ai voice", VoiceCommand.SpokenAnswers(false))
                put("enable ai voice", VoiceCommand.SpokenAnswers(true))
                put("set brightness", VoiceCommand.Brightness(50))
                put("exit kiosk", VoiceCommand.ExitKiosk)
                put("leave kiosk", VoiceCommand.ExitKiosk)
                put("set ai volume", VoiceCommand.AiVolume(50))
                SoundStream.entries.forEach { put("set ${if (it == SoundStream.UiEffects) "system" else it.label.lowercase()} volume", VoiceCommand.Volume(it, 50)) }
            }
    }
    val names: List<String> get() = commands.keys.toList() + "update profile"

    /** Exact current-turn commands must not depend on a model remembering to call a tool.
     * Only used after successful response/playback completion; never match history or substrings. */
    fun completedTurnActions(question: String, actions: List<VoiceCommand>): List<VoiceCommand> =
        if (VoiceGrammar.parse(question) == VoiceCommand.ExitKiosk) listOf(VoiceCommand.ExitKiosk)
        else actions

    fun resolve(name: String, percent: Int?, profile: Map<String, String>? = null): VoiceCommand? {
        if (name == "update profile") return if (percent == null && profile != null)
            ProfileUpdates.parse(profile)?.let(VoiceCommand::UpdateProfile) else null
        if (profile != null) return null
        return when (val command = commands[name]) {
        is VoiceCommand.Brightness -> percent?.takeIf { it in 0..100 }?.let { command.copy(percent = it.coerceAtLeast(5)) }
        is VoiceCommand.Volume -> percent?.takeIf { it in 0..100 }?.let { command.copy(percent = it) }
        is VoiceCommand.AiVolume -> percent?.takeIf { it in 0..100 }?.let { command.copy(percent = it) }
        else -> command.takeIf { percent == null }
        }
    }
}

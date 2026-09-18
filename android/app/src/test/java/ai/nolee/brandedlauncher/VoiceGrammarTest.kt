package ai.nolee.brandedlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceGrammarTest {
    @Test fun kioskExitPhrasesAreRecognized() {
        for (phrase in listOf("exit kiosk", "leave kiosk")) {
            assertTrue(phrase in VoiceGrammar.phrases)
            assertEquals(VoiceCommand.ExitKiosk, VoiceGrammar.parse(phrase))
        }
        assertNull(VoiceGrammar.parse("do not leave kiosk"))
    }
    @Test
    fun aiSettingsAreDistinctFromStartingAConversation() {
        for (name in listOf("a i", "cloud a i", "no lee a i")) {
            for (prefix in listOf("", "open ", "go to ", "show ")) {
                val phrase = "$prefix$name settings"
                assertTrue(phrase, phrase in VoiceGrammar.phrases)
                assertEquals(phrase, VoiceCommand.Open(Page.NoleeAi), VoiceGrammar.parse(phrase))
            }
            assertEquals(VoiceCommand.StartAi, VoiceGrammar.parse("open $name"))
        }
        assertEquals(VoiceCommand.Open(Page.NoleeAi), VoiceGrammar.parse("Open Nolee AI settings!"))
        assertEquals(VoiceCommand.Open(Page.NoleeAi), VoiceGrammar.parse("open ai settings"))
        assertEquals(VoiceCommand.Open(Page.System), VoiceGrammar.parse("open settings"))
    }

    @Test
    fun everyRecognisablePhraseRunsSomething() {
        val dead = VoiceGrammar.phrases.filter { it != "[unk]" && VoiceGrammar.parse(it) == null }
        assertEquals("phrases Vosk can hear but nothing runs: $dead", emptyList<String>(), dead)
    }

    @Test
    fun commandsMapToTheRightControl() {
        val cases = mapOf(
            "turn wi fi off" to VoiceCommand.Radio(wifi = true, on = false),
            "enable bluetooth" to VoiceCommand.Radio(wifi = false, on = true),
            "turn on adaptive brightness" to VoiceCommand.Adaptive(true),
            "set brightness to forty five percent" to VoiceCommand.Brightness(45),
            "screen one hundred percent" to VoiceCommand.Brightness(100),
            "set media volume to twenty percent" to VoiceCommand.Volume(SoundStream.Media, 20),
            "ring volume zero percent" to VoiceCommand.Volume(SoundStream.Ring, 0),
            "set volume to seventy three percent" to VoiceCommand.Volume(SoundStream.Media, 73),
            "mute all" to VoiceCommand.MuteAll,
            "restore sound" to VoiceCommand.RestoreSound,
            "open camera" to VoiceCommand.Launch(AppEntry.Camera),
            "launch s m s app" to VoiceCommand.Launch(AppEntry.SMS),
            "measure blood oxygen" to VoiceCommand.Open(Page.Oxygen),
            "open heart rate" to VoiceCommand.Open(Page.HeartRate),
            "wi fi settings" to VoiceCommand.Open(Page.Wifi),
            "open brightness settings" to VoiceCommand.Open(Page.Display),
            "show watch face" to VoiceCommand.Open(Page.Watch),
            "go home" to VoiceCommand.Open(Page.Home),
            "settings" to VoiceCommand.Open(Page.System),
            "open profile" to VoiceCommand.Open(Page.Profile),
            "open date and time" to VoiceCommand.Open(Page.DateTime),
            "time settings" to VoiceCommand.Open(Page.DateTime),
            "turn automatic time off" to VoiceCommand.AutoClock(zone = false, on = false),
            "turn automatic time zone on" to VoiceCommand.AutoClock(zone = true, on = true),
            "set time zone to tokyo" to VoiceCommand.Zone("Asia/Tokyo"),
            "edit my name" to VoiceCommand.EditProfile(ProfileField.Name),
            "change occupation" to VoiceCommand.EditProfile(ProfileField.Occupation),
        )
        cases.forEach { (phrase, expected) -> assertEquals(phrase, expected, VoiceGrammar.parse(phrase)) }
    }

    @Test
    fun everyTimeZoneAndProfileFieldCanBeSaid() {
        TIME_ZONES.forEach { (id, spoken) ->
            assertEquals(spoken, VoiceCommand.Zone(id), VoiceGrammar.parse("set time zone to $spoken"))
        }
        ProfileField.entries.forEach { field ->
            assertEquals(field.label, VoiceCommand.EditProfile(field), VoiceGrammar.parse("edit my ${field.label.lowercase()}"))
        }
    }

    @Test
    fun everyAskPromptSuggestsSomethingThatRuns() {
        AskPrompts.all.forEach { prompt ->
            assertTrue("Vosk cannot hear: ${prompt.example}", prompt.example in VoiceGrammar.phrases)
            assertNotNull(prompt.example, VoiceGrammar.parse(prompt.example))
        }
    }

    @Test
    fun unknownSpeechRunsNothing() {
        assertNull(VoiceGrammar.parse(""))
        assertNull(VoiceGrammar.parse("[unk]"))
        assertNull(VoiceGrammar.parse("[unk] [unk]"))
    }
}

package ai.nolee.brandedlauncher

import org.junit.Assert.*
import org.junit.Test

class CloudCommandsTest {
    @Test fun kioskExitAndAiVolumeAreBoundedTools() {
        for (name in listOf("exit kiosk", "leave kiosk")) {
            assertEquals(VoiceCommand.ExitKiosk, CloudCommands.resolve(name, null))
            assertNull(CloudCommands.resolve(name, 20))
        }
        for (percent in listOf(0, 35, 100)) {
            val command = CloudCommands.resolve("set ai volume", percent)
            assertEquals(VoiceCommand.AiVolume(percent), command)
            assertFalse(command!!.needsDeviceNavigation())
        }
        for (percent in listOf(null, -1, 101)) assertNull(CloudCommands.resolve("set ai volume", percent))
    }
    @Test fun conversationControlsAreExplicitAndDoNotChangeSystemVolume() {
        assertEquals(VoiceCommand.Transcript(true), CloudCommands.resolve("show transcript", null))
        assertEquals(VoiceCommand.Transcript(false), CloudCommands.resolve("hide transcript", null))
        assertEquals(VoiceCommand.SpokenAnswers(false), CloudCommands.resolve("mute ai voice", null))
        assertEquals(VoiceCommand.SpokenAnswers(true), CloudCommands.resolve("enable ai voice", null))
        assertNull(CloudCommands.resolve("mute ai voice", 0))
    }
    @Test fun coversEveryVoskAction() {
        val supported = CloudCommands.names.flatMap { name ->
            listOfNotNull(CloudCommands.resolve(name, null)) +
                (0..100).mapNotNull { CloudCommands.resolve(name, it) }
        }.toSet()
        val offline = VoiceGrammar.phrases.mapNotNull(VoiceGrammar::parse).toSet()
        assertTrue("Missing actions: ${offline - supported}", supported.containsAll(offline))
        assertTrue(CloudCommands.names.size <= 128)
        assertTrue(CloudCommands.names.all { it.matches(Regex("[a-z][a-z0-9 ]{0,79}")) })
    }
    @Test fun rejectsUnadvertisedAndMalformedActions() {
        assertNull(CloudCommands.resolve("su -c reboot", null))
        assertNull(CloudCommands.resolve("open camera", 50))
        assertNull(CloudCommands.resolve("set brightness", null))
        assertNull(CloudCommands.resolve("set brightness", -1))
        assertNull(CloudCommands.resolve("set brightness", 101))
        assertEquals(VoiceCommand.Brightness(5), CloudCommands.resolve("set brightness", 0))
        assertEquals(VoiceCommand.Brightness(43), CloudCommands.resolve("set brightness", 43))
    }
}

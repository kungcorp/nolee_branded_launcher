package ai.nolee.brandedlauncher

import org.junit.Assert.*
import org.junit.Test

class ProfileUpdatesTest {
    @Test fun countryIsAnIndependentBoundedProfileField() {
        assertEquals(mapOf(ProfileField.Name to "Alex", ProfileField.Country to "HK"),
            ProfileUpdates.parse(mapOf("name" to "Alex", "country" to "HK")))
        assertNull(ProfileUpdates.parse(mapOf("country" to "x".repeat(41))))
        assertNull(ProfileUpdates.parse(mapOf("country" to "Hong\nKong")))
        assertEquals(VoiceCommand.EditProfile(ProfileField.Country), VoiceGrammar.parse("edit my country"))
    }
    @Test fun parsesBatchedFieldsWithoutNavigation() {
        val command = CloudCommands.resolve("update profile", null, mapOf("name" to " Alex ", "age" to "32", "city" to "Hong Kong"))
        assertEquals(VoiceCommand.UpdateProfile(mapOf(ProfileField.Name to "Alex", ProfileField.Age to "32", ProfileField.City to "Hong Kong")), command)
        assertFalse(command!!.needsDeviceNavigation())
        assertTrue(VoiceCommand.Brightness(50).needsDeviceNavigation())
        assertTrue(VoiceCommand.EditProfile(ProfileField.Name).needsDeviceNavigation())
        assertFalse(VoiceCommand.Transcript(true).needsDeviceNavigation())
    }
    @Test fun rejectsUnknownFieldsInvalidValuesAndWrongCommandArguments() {
        for (patch in listOf(emptyMap(), mapOf("root" to "yes"), mapOf("age" to "-1"), mapOf("age" to "151"),
            mapOf("age" to "ten"), mapOf("name" to "x".repeat(21)), mapOf("city" to "a\nb"))) {
            assertNull(ProfileUpdates.parse(patch))
        }
        assertNull(CloudCommands.resolve("update profile", 50, mapOf("name" to "Alex")))
        assertNull(CloudCommands.resolve("set brightness", 50, mapOf("name" to "Alex")))
        assertNull(CloudCommands.resolve("update profile", null))
        assertEquals(mapOf(ProfileField.Name to ""), ProfileUpdates.parse(mapOf("name" to "")))
    }
    @Test fun offlineAiAliasesOpenPersona() {
        for (phrase in listOf("open no lee a i", "start a i", "turn on cloud a i", "open nolee ai"))
            assertEquals(VoiceCommand.StartAi, VoiceGrammar.parse(phrase))
        assertTrue(VoiceGrammar.phrases.contains("open no lee a i"))
        assertFalse(VoiceCommand.StartAi.needsDeviceNavigation())
    }
}

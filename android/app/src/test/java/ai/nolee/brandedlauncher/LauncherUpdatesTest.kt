package ai.nolee.brandedlauncher

import org.junit.Assert.*
import org.junit.Test

class LauncherUpdatesTest {
    @Test fun shutdownUsesExistingPowerPage() {
        assertEquals(Page.Power, SystemEntry.Shutdown.page)
        assertEquals("Shutdown", SystemEntry.Shutdown.label)
    }
    @Test fun appPromptFitsGatewayAndExplainsMemoryAndExtensibility() {
        assertTrue("Prompt exceeds gateway limit: ${CloudAi.APP_PROMPT.length}", CloudAi.APP_PROMPT.length <= 4000)
        assertTrue(CloudAi.APP_PROMPT.contains("five most recent"))
        assertTrue(CloudAi.APP_PROMPT.contains("coding agent"))
        assertTrue(CloudAi.APP_PROMPT.contains("Quick Command"))
    }
}

package ai.nolee.brandedlauncher

import org.junit.Assert.*
import org.junit.Test

class CloudPromptTest {
    @Test fun completeRequestPromptFitsGatewayLimit() {
        for (percent in 0..100) {
            val prompt = CloudAi.appPrompt(percent)
            assertTrue(prompt.length <= 4000)
            assertTrue(prompt.contains("Current AI/media volume: $percent percent."))
            assertTrue(prompt.contains("set ai volume"))
            assertTrue(prompt.contains("exit kiosk"))
        }
    }
}

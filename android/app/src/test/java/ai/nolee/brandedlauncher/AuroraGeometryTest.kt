package ai.nolee.brandedlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class AuroraGeometryTest {
    @Test fun haloMatchesFullScreenDial() {
        assertEquals(184.15f, FULL_WATCH_RADIUS, .001f)
        assertEquals(145f * IDLE_SCALE, FULL_WATCH_RADIUS, .001f)
        assertEquals(147.32f, FULL_WATCH_OUTLINE_RADIUS, .001f)
    }

    @Test fun entranceClampsAndHasSmoothMidpoint() {
        assertEquals(0f, auroraSmooth(-1f), 0f)
        assertEquals(0f, auroraSmooth(0f), 0f)
        assertEquals(.5f, auroraSmooth(.5f), .0001f)
        assertEquals(1f, auroraSmooth(1f), 0f)
        assertEquals(1f, auroraSmooth(2f), 0f)
    }
}

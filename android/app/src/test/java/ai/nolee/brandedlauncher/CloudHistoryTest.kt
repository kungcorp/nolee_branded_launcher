package ai.nolee.brandedlauncher

import org.junit.Assert.*
import org.junit.Test

class CloudHistoryTest {
    @Test fun onlyFiveCompletedNonemptyPairsAreSubmitted() {
        val turns = (1..7).map { CloudAiTurn(it, "Q$it", "A$it", completed = true) } +
            CloudAiTurn(8, "interrupted", "partial") +
            CloudAiTurn(9, "", "answer", completed = true)
        assertEquals(listOf(3, 4, 5, 6, 7), recentCloudHistory(turns).map { it.id })
    }
    @Test fun freshSessionHasNoHistory() {
        assertTrue(recentCloudHistory(CloudAiState().turns).isEmpty())
    }
}

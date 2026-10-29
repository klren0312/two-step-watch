package com.klkl.tracknav.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationRecoveryTest {

    @Test
    fun `cold start is never interrupted before the first fix`() {
        assertEquals(
            LocationRecoveryAction.NONE,
            locationRecoveryAction(
                nowElapsedMillis = 600_000L,
                lastFixElapsedMillis = 0L,
                lastFullRestartElapsedMillis = 0L,
                hasFix = false,
                hasRestarted = false,
            ),
        )
    }

    @Test
    fun `recent fix needs no recovery`() {
        assertEquals(
            LocationRecoveryAction.NONE,
            locationRecoveryAction(
                nowElapsedMillis = 200_000L,
                lastFixElapsedMillis = 198_000L,
                lastFullRestartElapsedMillis = 100_000L,
                hasFix = true,
                hasRestarted = true,
            ),
        )
    }

    @Test
    fun `brief outage waits before recovery`() {
        assertEquals(
            LocationRecoveryAction.NONE,
            locationRecoveryAction(
                nowElapsedMillis = 20_000L,
                lastFixElapsedMillis = 0L,
                lastFullRestartElapsedMillis = 0L,
                hasFix = true,
                hasRestarted = false,
            ),
        )
    }

    @Test
    fun `lost fix triggers one full restart`() {
        assertEquals(
            LocationRecoveryAction.FULL_RESTART,
            locationRecoveryAction(
                nowElapsedMillis = 100_000L,
                lastFixElapsedMillis = 60_000L,
                lastFullRestartElapsedMillis = 0L,
                hasFix = true,
                hasRestarted = false,
            ),
        )
    }

    @Test
    fun `restart respects the cooldown`() {
        assertEquals(
            LocationRecoveryAction.NONE,
            locationRecoveryAction(
                nowElapsedMillis = 200_000L,
                lastFixElapsedMillis = 100_000L,
                lastFullRestartElapsedMillis = 100_000L,
                hasFix = true,
                hasRestarted = true,
            ),
        )
    }

    @Test
    fun `restart retries after the cooldown`() {
        assertEquals(
            LocationRecoveryAction.FULL_RESTART,
            locationRecoveryAction(
                nowElapsedMillis = 300_000L,
                lastFixElapsedMillis = 60_000L,
                lastFullRestartElapsedMillis = 100_000L,
                hasFix = true,
                hasRestarted = true,
            ),
        )
    }
}

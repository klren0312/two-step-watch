package com.klkl.tracknav.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationRecoveryTest {

    @Test
    fun `recent fix needs no recovery`() {
        assertEquals(
            LocationRecoveryAction.NONE,
            locationRecoveryAction(
                nowElapsedMillis = 200_000L,
                lastFixElapsedMillis = 198_000L,
                lastRegisterElapsedMillis = 190_000L,
                lastFullRestartElapsedMillis = 100_000L,
            ),
        )
    }

    @Test
    fun `no recovery before the silence threshold`() {
        assertEquals(
            LocationRecoveryAction.NONE,
            locationRecoveryAction(
                nowElapsedMillis = 14_000L,
                lastFixElapsedMillis = 1_000L,
                lastRegisterElapsedMillis = 1_000L,
                lastFullRestartElapsedMillis = 1_000L,
            ),
        )
    }

    @Test
    fun `reregisters once the fix is old enough`() {
        assertEquals(
            LocationRecoveryAction.REREGISTER,
            locationRecoveryAction(
                nowElapsedMillis = 20_000L,
                lastFixElapsedMillis = 0L,
                lastRegisterElapsedMillis = 0L,
                lastFullRestartElapsedMillis = 0L,
            ),
        )
    }

    @Test
    fun `does not reregister too soon after the previous attempt`() {
        assertEquals(
            LocationRecoveryAction.NONE,
            locationRecoveryAction(
                nowElapsedMillis = 20_000L,
                lastFixElapsedMillis = 0L,
                lastRegisterElapsedMillis = 12_000L,
                lastFullRestartElapsedMillis = 0L,
            ),
        )
    }

    @Test
    fun `brief outage after a healthy period only reregisters`() {
        assertEquals(
            LocationRecoveryAction.REREGISTER,
            locationRecoveryAction(
                nowElapsedMillis = 100_000L,
                lastFixElapsedMillis = 85_000L,
                lastRegisterElapsedMillis = 80_000L,
                lastFullRestartElapsedMillis = 10_000L,
            ),
        )
    }

    @Test
    fun `full restart after a minute without a fix`() {
        assertEquals(
            LocationRecoveryAction.FULL_RESTART,
            locationRecoveryAction(
                nowElapsedMillis = 70_000L,
                lastFixElapsedMillis = 0L,
                lastRegisterElapsedMillis = 0L,
                lastFullRestartElapsedMillis = 0L,
            ),
        )
    }

    @Test
    fun `full restart is followed by cheaper reregistrations`() {
        val fullRestartAt = 70_000L
        assertEquals(
            LocationRecoveryAction.REREGISTER,
            locationRecoveryAction(
                nowElapsedMillis = fullRestartAt + 15_000L,
                lastFixElapsedMillis = 0L,
                lastRegisterElapsedMillis = fullRestartAt,
                lastFullRestartElapsedMillis = fullRestartAt,
            ),
        )
        assertEquals(
            LocationRecoveryAction.FULL_RESTART,
            locationRecoveryAction(
                nowElapsedMillis = fullRestartAt + 60_000L,
                lastFixElapsedMillis = 0L,
                lastRegisterElapsedMillis = fullRestartAt,
                lastFullRestartElapsedMillis = fullRestartAt,
            ),
        )
    }
}

package com.klkl.tracknav.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AmbientGuidanceTest {

    @Test
    fun `bearing delta wraps across north`() {
        assertEquals(-5f, normalizeBearingDelta(-5.0), 0.01f)
        assertEquals(-10f, normalizeBearingDelta(350.0), 0.01f)
        assertEquals(10f, normalizeBearingDelta(-350.0), 0.01f)
    }

    @Test
    fun `bearing delta keeps straight and reverse directions`() {
        assertEquals(0f, normalizeBearingDelta(0.0), 0.01f)
        assertEquals(180f, normalizeBearingDelta(180.0), 0.01f)
        assertEquals(-180f, normalizeBearingDelta(-180.0), 0.01f)
    }

    @Test
    fun `bearing delta normalizes full turns`() {
        assertEquals(20f, normalizeBearingDelta(380.0), 0.01f)
        assertEquals(-20f, normalizeBearingDelta(-380.0), 0.01f)
    }
}

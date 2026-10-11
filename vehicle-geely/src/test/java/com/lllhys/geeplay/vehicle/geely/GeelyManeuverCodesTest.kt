package com.lllhys.geeplay.vehicle.geely

import org.junit.Assert.assertEquals
import org.junit.Test

class GeelyManeuverCodesTest {
    @Test
    fun `maps every documented Apple maneuver`() {
        val expected = mapOf(
            0 to 0, 1 to 102, 2 to 103, 3 to 109, 4 to 108, 5 to 120,
            6 to 15, 7 to 15, 8 to 12, 9 to 13, 10 to 115, 11 to 101,
            12 to 22, 13 to 48, 14 to 49, 15 to 18, 16 to 18, 17 to 18,
            18 to 108, 19 to 44, 20 to 102, 21 to 103, 22 to 47, 23 to 46,
            24 to 115, 25 to 115, 26 to 108, 27 to 115, 45 to 45, 46 to 45,
            47 to 106, 48 to 107, 49 to 104, 50 to 105, 51 to 0, 52 to 109,
            53 to 109,
        ) + (28..44).associateWith { it + 1 }

        for ((apple, geely) in expected) {
            assertEquals("Apple maneuver $apple", geely, GeelyManeuverCodes.turnId(apple, GeelyNavigationBackend.ECARX_FLYME, false))
        }
        assertEquals(0, GeelyManeuverCodes.turnId(255, GeelyNavigationBackend.ECARX_FLYME, false))
    }

    @Test
    fun `OneOS raw mode preserves a bounded Apple value`() {
        assertEquals(44, GeelyManeuverCodes.turnId(44, GeelyNavigationBackend.ONE_OS, true))
        assertEquals(255, GeelyManeuverCodes.turnId(999, GeelyNavigationBackend.ONE_OS, true))
        assertEquals(0, GeelyManeuverCodes.turnId(-1, GeelyNavigationBackend.ONE_OS, true))
    }

    @Test
    fun `non OneOS backends never enable raw ids`() {
        assertEquals(29, GeelyManeuverCodes.turnId(28, GeelyNavigationBackend.AUTO, true))
        assertEquals(29, GeelyManeuverCodes.turnId(28, GeelyNavigationBackend.ECARX_FLYME, true))
    }
}

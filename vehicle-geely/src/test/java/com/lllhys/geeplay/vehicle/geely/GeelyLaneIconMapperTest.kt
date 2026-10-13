package com.lllhys.geeplay.vehicle.geely

import com.shilapi.xcertplay.navigation.apple.AppleLane
import com.shilapi.xcertplay.navigation.apple.AppleLaneStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class GeelyLaneIconMapperTest {
    @Test
    fun `maps factory-confirmed basic lane icons`() {
        assertEquals(0, icon(AppleLaneStatus.GOOD, 0))
        assertEquals(1, icon(AppleLaneStatus.GOOD, -45))
        assertEquals(3, icon(AppleLaneStatus.GOOD, 45))
        assertEquals(4, icon(AppleLaneStatus.GOOD, 0, 45))
        assertEquals(8, icon(AppleLaneStatus.GOOD, 180))
    }

    @Test
    fun `maps factory-confirmed recommended lane icons`() {
        assertEquals(15, icon(AppleLaneStatus.PREFERRED, 0, highlight = 0))
        assertEquals(16, icon(AppleLaneStatus.PREFERRED, -45, highlight = -45))
        assertEquals(18, icon(AppleLaneStatus.PREFERRED, 45, highlight = 45))
        assertEquals(32, icon(AppleLaneStatus.PREFERRED, 0, 45, highlight = 0))
        assertEquals(33, icon(AppleLaneStatus.PREFERRED, 0, 45, highlight = 45))
    }

    private fun icon(
        status: AppleLaneStatus,
        vararg angles: Int,
        highlight: Int? = null,
    ): Int = GeelyLaneIconMapper.iconId(AppleLane(0, status, angles.toList(), highlight))
}

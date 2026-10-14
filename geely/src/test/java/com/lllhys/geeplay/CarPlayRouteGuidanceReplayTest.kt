package com.lllhys.geeplay

import com.lllhys.geeplay.vehicle.geely.GeelyLaneIconMapper
import com.shilapi.xcertplay.navigation.apple.AppleRouteGuidanceChange
import com.shilapi.xcertplay.navigation.apple.AppleRouteGuidanceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlayRouteGuidanceReplayTest {
    @Test
    fun `replay drives real parser and store through recommended lane transitions`() {
        val store = AppleRouteGuidanceStore()

        CarPlayRouteGuidanceReplay.phases().forEach { phase ->
            var lastChange = AppleRouteGuidanceChange.NONE
            phase.frames.forEach { frame -> lastChange = store.accept(frame.messageId, frame.payload) }

            assertEquals(AppleRouteGuidanceChange.UPDATED, lastChange)
            val snapshot = store.current()
            assertNotNull(snapshot)
            assertEquals(
                phase.expectedOemLaneIconIds,
                snapshot!!.laneGuidance?.lanes.orEmpty().map(GeelyLaneIconMapper::iconId),
            )
        }

        assertEquals(
            AppleRouteGuidanceChange.CLEARED,
            CarPlayRouteGuidanceReplay.routeEnded().let { store.accept(it.messageId, it.payload) },
        )
        assertNull(store.current())
    }
}

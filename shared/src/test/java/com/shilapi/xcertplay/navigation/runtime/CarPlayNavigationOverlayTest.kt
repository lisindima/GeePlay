package com.shilapi.xcertplay.navigation.runtime

import com.shilapi.xcertplay.navigation.apple.AppleJunctionGuidance
import com.shilapi.xcertplay.navigation.apple.AppleManeuver
import com.shilapi.xcertplay.navigation.apple.AppleNavigationSnapshot
import com.shilapi.xcertplay.navigation.apple.AppleRouteState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlayNavigationOverlayTest {
    @After fun reset() { CarPlayNavigationOverlay.setListener(null); CarPlayNavigationOverlay.close() }

    @Test
    fun `publishes generic maneuver and junction geometry`() {
        val events = ArrayList<com.shilapi.xcertplay.hud.ClusterTurnGuidance?>()
        CarPlayNavigationOverlay.setListener(events::add)
        val source = RouteGuidanceSource.create(RouteGuidanceSourceKind.WIRED)
        val junction = AppleJunctionGuidance(2, listOf(-90, 0, 45), 45)

        CarPlayNavigationOverlay.publish(
            NavigationPublication(
                source,
                1,
                AppleNavigationSnapshot(
                    generation = source.id,
                    revision = 1,
                    routeState = AppleRouteState.ROUTE_SET,
                    currentRoad = "Current",
                    maneuver = AppleManeuver(7, 2, 0, "Next", null, junction),
                    nextDistanceMeters = 120,
                    remainingMeters = 5_000,
                    remainingSeconds = 600,
                    arrivalEpochSeconds = 1_800,
                    laneGuidance = null,
                    updatedAtNanos = 1,
                ),
            ),
        )

        val published = events.last()!!
        assertEquals("Next", published.road)
        assertEquals(junction, published.junction)
        assertEquals(5_000L, published.remainingMeters)
    }

    @Test
    fun `source replacement and route end both clear stale cards`() {
        val events = ArrayList<com.shilapi.xcertplay.hud.ClusterTurnGuidance?>()
        CarPlayNavigationOverlay.setListener(events::add)
        val source = RouteGuidanceSource.create(RouteGuidanceSourceKind.WIRED)
        CarPlayNavigationOverlay.publish(
            NavigationPublication(
                source,
                1,
                AppleNavigationSnapshot(
                    source.id, 1, AppleRouteState.ROUTE_SET, "",
                    AppleManeuver(1, 1, 0, "Road", null, null),
                    50, null, null, null, null, 1,
                ),
            ),
        )
        val before = events.size

        CarPlayNavigationOverlay.clear(NavigationClear(source, 1, NavigationClearReason.SOURCE_REPLACED))
        assertEquals(before + 1, events.size)
        assertNull(events.last())

        CarPlayNavigationOverlay.publish(
            NavigationPublication(
                source,
                1,
                AppleNavigationSnapshot(
                    source.id, 2, AppleRouteState.ROUTE_SET, "",
                    AppleManeuver(1, 1, 0, "Road", null, null),
                    40, null, null, null, null, 2,
                ),
            ),
        )
        CarPlayNavigationOverlay.clear(NavigationClear(source, 1, NavigationClearReason.ROUTE_ENDED))
        assertNull(events.last())
    }
}

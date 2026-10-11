package com.lllhys.geeplay.vehicle.geely

import com.shilapi.xcertplay.navigation.apple.AppleJunctionGuidance
import com.shilapi.xcertplay.navigation.apple.AppleManeuver
import com.shilapi.xcertplay.navigation.apple.AppleNavigationSnapshot
import com.shilapi.xcertplay.navigation.apple.AppleRouteState
import com.shilapi.xcertplay.navigation.runtime.NavigationClear
import com.shilapi.xcertplay.navigation.runtime.NavigationOutput
import com.shilapi.xcertplay.navigation.runtime.NavigationPublication
import com.shilapi.xcertplay.navigation.runtime.RouteGuidanceSource
import com.shilapi.xcertplay.navigation.runtime.RouteGuidanceSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeelyTurnOverlayOutputTest {
    @Test
    fun `junction geometry is stripped while the experimental gate is disabled`() {
        val delegate = RecordingOutput()
        GeelyTurnOverlayOutput(false, delegate).publish(publication())

        assertNull(delegate.published!!.snapshot.maneuver.junction)
    }

    @Test
    fun `junction geometry is retained only when the experimental gate is enabled`() {
        val delegate = RecordingOutput()
        GeelyTurnOverlayOutput(true, delegate).publish(publication())

        assertEquals(45, delegate.published!!.snapshot.maneuver.junction?.exitAngle)
    }

    private class RecordingOutput : NavigationOutput {
        var published: NavigationPublication? = null
        override fun publish(publication: NavigationPublication) { published = publication }
        override fun clear(clear: NavigationClear) = Unit
    }

    private fun publication(): NavigationPublication {
        val source = RouteGuidanceSource.create(RouteGuidanceSourceKind.WIRED)
        return NavigationPublication(
            source,
            1,
            AppleNavigationSnapshot(
                source.id,
                1,
                AppleRouteState.ROUTE_SET,
                "",
                AppleManeuver(
                    1,
                    2,
                    0,
                    "Road",
                    null,
                    AppleJunctionGuidance(1, listOf(0, 45), 45),
                ),
                100,
                null,
                null,
                null,
                null,
                1,
            ),
        )
    }
}

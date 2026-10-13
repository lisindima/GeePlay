package com.lllhys.geeplay.vehicle.geely

import com.ecarx.xui.adaptapi.diminteraction.DimInteraction
import com.shilapi.xcertplay.navigation.apple.AppleLane
import com.shilapi.xcertplay.navigation.apple.AppleLaneGuidance
import com.shilapi.xcertplay.navigation.apple.AppleLaneStatus
import com.shilapi.xcertplay.navigation.apple.AppleManeuver
import com.shilapi.xcertplay.navigation.apple.AppleNavigationSnapshot
import com.shilapi.xcertplay.navigation.apple.AppleRouteState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GeelyDimNavigationAdapterTest {
    @Test
    fun `watched connection publishes navigation and typed lanes then clears`() {
        val statuses = ArrayList<GeelyNavigationDriverStatus>()
        val driver = GeelyDimNavigationAdapter(
            RuntimeEnvironment.getApplication(),
            config(GeelyNavigationBackend.ECARX_FLYME, lanes = true),
            onStatus = statuses::add,
        )

        assertTrue(driver.start())
        assertTrue(driver.publish(snapshot()))

        val dim = DimInteraction.latest!!
        assertEquals(listOf("registerWatcher", "connect", "getNaviInteraction", "switch:1:::true"), dim.calls.take(4))
        assertEquals(103, dim.navigation.lastTurnId)
        assertEquals(240L, dim.navigation.lastNextDistance)
        assertEquals(1_500L, dim.navigation.lastDestination)
        assertEquals(320L, dim.navigation.lastEta)
        assertEquals("Harbor Road", dim.navigation.lastRoad)
        assertEquals(2, dim.navigation.lastLanes.size)
        assertEquals(0, dim.navigation.lastLanes[0].number)
        assertEquals(0, dim.navigation.lastLanes[0].iconId)
        assertEquals(33, dim.navigation.lastLanes[1].iconId)
        assertEquals(GeelyNavigationDriverState.ACTIVE, statuses.last().state)

        assertTrue(driver.clear())
        assertEquals(0, dim.navigation.lastStatus)
        assertTrue(dim.navigation.lastLanes.isEmpty())
        assertTrue("stopped" in dim.navigation.calls)
        assertTrue("switch:1:::false" in dim.calls)

        driver.close()
        assertTrue("unregisterCallback" in dim.navigation.calls)
        assertTrue("unregisterWatcher" in dim.calls)
        assertTrue("disconnect" in dim.calls)
    }

    @Test
    fun `direct OneOS mode skips watcher and preserves raw icon`() {
        val driver = GeelyDimNavigationAdapter(
            RuntimeEnvironment.getApplication(),
            config(GeelyNavigationBackend.ONE_OS, lanes = false, originalIcons = true),
        )

        assertTrue(driver.start())
        assertTrue(driver.publish(snapshot(maneuverType = 44, lanes = null)))

        val dim = DimInteraction.latest!!
        assertFalse(dim.calls.contains("registerWatcher"))
        assertFalse(dim.calls.contains("connect"))
        assertEquals(44, dim.navigation.lastTurnId)
        driver.close()
    }

    @Test
    fun `automatic mode falls back to direct when watched connection contract is absent`() {
        val driver = GeelyDimNavigationAdapter(
            RuntimeEnvironment.getApplication(),
            config(GeelyNavigationBackend.AUTO, lanes = false),
            loadClass = { name ->
                if (name == GeelyDimNavigationAdapter.CONNECT_WATCHER) throw ClassNotFoundException(name)
                Class.forName(name)
            },
        )

        assertTrue(driver.start())
        assertTrue(driver.publish(snapshot(lanes = null)))

        val dim = DimInteraction.latest!!
        assertFalse(dim.calls.contains("registerWatcher"))
        assertFalse(dim.calls.contains("connect"))
        driver.close()
    }

    @Test
    fun `OEM rejection fails closed and records cleanup uncertainty`() {
        val statuses = ArrayList<GeelyNavigationDriverStatus>()
        val driver = GeelyDimNavigationAdapter(
            RuntimeEnvironment.getApplication(),
            config(GeelyNavigationBackend.ONE_OS, lanes = false),
            onStatus = statuses::add,
        )
        assertTrue(driver.start())
        DimInteraction.latest!!.rejectedMethod = "switch"

        assertFalse(driver.publish(snapshot(lanes = null)))

        assertTrue(driver.failed)
        assertTrue(driver.cleanupPending)
        assertEquals(GeelyNavigationDriverState.CLEANUP_PENDING, statuses.last().state)
    }

    @Test
    fun `generation guard is rechecked between OEM calls`() {
        var current = true
        val driver = GeelyDimNavigationAdapter(
            RuntimeEnvironment.getApplication(),
            config(GeelyNavigationBackend.ONE_OS, lanes = false),
        )
        driver.setExecutionGuard { current }
        assertTrue(driver.start())
        val dim = DimInteraction.latest!!
        dim.afterCall = { method -> if (method == "switch") current = false }

        assertFalse(driver.publish(snapshot(lanes = null)))

        assertTrue("The first OEM mode switch was attempted", "switch:1:::true" in dim.calls)
        assertFalse("A stale generation must not announce an active route", "status:1" in dim.navigation.calls)
        assertFalse("A stale generation must not start turn-by-turn", "started" in dim.navigation.calls)
        driver.close()
    }

    private fun config(
        backend: GeelyNavigationBackend,
        lanes: Boolean,
        originalIcons: Boolean = false,
    ) = GeelyNavigationConfig(
        enabled = true,
        backend = backend,
        laneGuidanceEnabled = lanes,
        originalOneOsIcons = originalIcons,
        laneNumberBase = 0,
    )

    private fun snapshot(
        maneuverType: Int = 2,
        lanes: AppleLaneGuidance? = AppleLaneGuidance(
            eventIndex = 7,
            description = "Keep right",
            lanes = listOf(
                AppleLane(0, AppleLaneStatus.GOOD, listOf(0), null),
                AppleLane(1, AppleLaneStatus.PREFERRED, listOf(0, 45), 45),
            ),
        ),
    ) = AppleNavigationSnapshot(
        generation = 1,
        revision = 1,
        routeState = AppleRouteState.ROUTE_SET,
        currentRoad = "Current Road",
        maneuver = AppleManeuver(4, maneuverType, 0, "Harbor Road", 7, null),
        nextDistanceMeters = 240,
        remainingMeters = 1_500,
        remainingSeconds = 320,
        arrivalEpochSeconds = null,
        laneGuidance = lanes,
        updatedAtNanos = 1,
    )
}

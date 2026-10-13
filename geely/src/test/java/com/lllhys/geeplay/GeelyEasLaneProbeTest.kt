package com.lllhys.geeplay

import com.ecarx.eas.framework.sdk.navi.MapAPI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GeelyEasLaneProbeTest {
    @Test
    fun `establishes guiding provider before lanes and unregisters cleanly`() {
        val callbacks = ArrayList<String>()
        val report = GeelyEasLaneProbe(RuntimeEnvironment.getApplication()).run(
            lanes = listOf(0 to 16, 1 to 0, 2 to 4),
            durationMillis = 0,
            onVisible = { callbacks += "visible" },
            onBeforeHidden = { callbacks += "hidden" },
        )

        val api = MapAPI.last!!
        assertEquals(
            listOf("status:16", "status:43", "lanes:true", "lanes:false", "status:44", "status:17"),
            api.events,
        )
        assertEquals(listOf(16, 43, 44, 17), api.statuses)
        assertEquals(listOf(16, 0, 4), api.lanes.first().laneItemList.map { it.laneIconId })
        assertEquals(listOf(0, 1, 2), api.lanes.first().laneItemList.map { it.laneIndex })
        assertTrue(api.lanes.first().lanesEnabled)
        assertTrue(!api.lanes.last().lanesEnabled)
        assertTrue(api.registered)
        assertTrue(api.unregistered)
        assertTrue(api.released)
        assertEquals(-1, api.requestedMapVendor)
        assertEquals(listOf("visible", "hidden"), callbacks)
        assertTrue("requestedMapVendor=unset" in report)
        assertTrue("registeredMapVendor=31415" in report)
        assertTrue("guideStarted=true" in report)
        assertTrue("guideStopped=true" in report)
        assertTrue("result=DELIVERED" in report)
    }

    @Test
    fun `refuses unknown vendor before publishing vehicle output`() {
        MapAPI.registeredMapVendor = -100
        try {
            val report = GeelyEasLaneProbe(RuntimeEnvironment.getApplication()).run(
                lanes = listOf(0 to 16, 1 to 0, 2 to 4),
                durationMillis = 0,
            )

            val api = MapAPI.last!!
            assertTrue(api.events.isEmpty())
            assertTrue(api.registered)
            assertTrue(api.unregistered)
            assertTrue(api.released)
            assertTrue("registeredMapVendor=-100" in report)
            assertTrue("guideStarted=false" in report)
            assertTrue("result=FAILED" in report)
            assertTrue("a supported provider identity is required" in report)
        } finally {
            MapAPI.registeredMapVendor = 31_415
        }
    }
}

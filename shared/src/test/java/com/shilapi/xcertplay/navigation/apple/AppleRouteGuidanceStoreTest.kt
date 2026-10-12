package com.shilapi.xcertplay.navigation.apple

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppleRouteGuidanceStoreTest {
    @Test
    fun `combines maneuver route and lane events into one snapshot`() {
        val store = AppleRouteGuidanceStore(generation = 7)

        assertEquals(
            AppleRouteGuidanceChange.NONE,
            store.accept(
                AppleRouteGuidanceParser.ROUTE_GUIDANCE_MANEUVER_UPDATE,
                tlvs(
                    tlv(0x0001, 0, 4),
                    tlv(0x0003, 2),
                    tlv(0x0004, *utf8z("Second Street")),
                    tlv(0x0008, 1),
                    tlv(0x000c, 0, 9),
                ),
            ),
        )
        assertEquals(
            AppleRouteGuidanceChange.NONE,
            store.accept(
                AppleRouteGuidanceParser.LANE_GUIDANCE_INFORMATION,
                laneEvent(
                    eventIndex = 9,
                    lane(
                        position = 0,
                        status = AppleLaneStatus.GOOD,
                        angles = intArrayOf(-45, 0),
                    ),
                    lane(
                        position = 1,
                        status = AppleLaneStatus.PREFERRED,
                        angles = intArrayOf(0, 45),
                        highlight = 45,
                    ),
                ),
            ),
        )

        val change = store.accept(
            AppleRouteGuidanceParser.ROUTE_GUIDANCE_UPDATE,
            tlvs(
                tlv(0x0001, 1),
                tlv(0x0003, *utf8z("First Street")),
                tlv(0x0006, *u64(120)),
                tlv(0x0007, 0, 0, 3, 0xe8),
                tlv(0x000a, 0, 0, 0, 150),
                tlv(0x000d, 0, 4),
                tlv(0x0010, 0, 9),
                tlv(0x0011, 0, 1),
                tlv(0x0012, 1),
            ),
        )

        assertEquals(AppleRouteGuidanceChange.UPDATED, change)
        val snapshot = store.current()!!
        assertEquals(7, snapshot.generation)
        assertEquals(2, snapshot.maneuver.type)
        assertEquals(1, snapshot.maneuver.drivingSide)
        assertEquals("Second Street", snapshot.maneuver.afterRoad)
        assertEquals(150, snapshot.nextDistanceMeters)
        assertEquals(1_000L, snapshot.remainingMeters)
        assertEquals(120L, snapshot.remainingSeconds)
        assertEquals(9, snapshot.laneGuidance?.eventIndex)
        assertEquals(2, snapshot.laneGuidance?.lanes?.size)
        assertEquals(listOf(-45, 0), snapshot.laneGuidance?.lanes?.first()?.anglesDegrees)
        assertEquals(45, snapshot.laneGuidance?.lanes?.last()?.highlightAngleDegrees)
    }

    @Test
    fun `future lane event does not replace selected lane`() {
        val store = populatedStore(laneIndex = 3)
        store.accept(
            AppleRouteGuidanceParser.LANE_GUIDANCE_INFORMATION,
            laneEvent(3, lane(0, AppleLaneStatus.PREFERRED, intArrayOf(0))),
        )
        val selected = store.current()!!.laneGuidance

        val change = store.accept(
            AppleRouteGuidanceParser.LANE_GUIDANCE_INFORMATION,
            laneEvent(8, lane(0, AppleLaneStatus.GOOD, intArrayOf(90))),
        )

        assertEquals(AppleRouteGuidanceChange.NONE, change)
        assertEquals(selected, store.current()!!.laneGuidance)
    }

    @Test
    fun `route update selects a cached future lane event`() {
        val store = populatedStore(laneIndex = 3)
        store.accept(
            AppleRouteGuidanceParser.LANE_GUIDANCE_INFORMATION,
            laneEvent(8, lane(0, AppleLaneStatus.PREFERRED, intArrayOf(-90))),
        )

        val change = store.accept(
            AppleRouteGuidanceParser.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x0010, 0, 8), tlv(0x0012, 1)),
        )

        assertEquals(AppleRouteGuidanceChange.UPDATED, change)
        assertEquals(8, store.current()!!.laneGuidance?.eventIndex)
    }

    @Test
    fun `lane showing false removes lanes without ending the route`() {
        val store = populatedStore(laneIndex = 3)
        store.accept(
            AppleRouteGuidanceParser.LANE_GUIDANCE_INFORMATION,
            laneEvent(3, lane(0, AppleLaneStatus.PREFERRED, intArrayOf(0))),
        )
        assertNotNull(store.current()!!.laneGuidance)

        val change = store.accept(
            AppleRouteGuidanceParser.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x0012, 0)),
        )

        assertEquals(AppleRouteGuidanceChange.UPDATED, change)
        assertNotNull(store.current())
        assertNull(store.current()!!.laneGuidance)
    }

    @Test
    fun `malformed lane event leaves visible state unchanged`() {
        val store = populatedStore(laneIndex = 3)
        store.accept(
            AppleRouteGuidanceParser.LANE_GUIDANCE_INFORMATION,
            laneEvent(3, lane(0, AppleLaneStatus.GOOD, intArrayOf(0))),
        )
        val before = store.current()
        val malformedNested = tlvs(
            tlv(0x0000, 0, 0),
            tlv(0x0001, AppleLaneStatus.PREFERRED.wireValue),
            tlv(0x0002, 0),
        )

        val change = store.accept(
            AppleRouteGuidanceParser.LANE_GUIDANCE_INFORMATION,
            tlvs(tlv(0x0001, 0, 3), tlvBytes(0x0002, malformedNested)),
        )

        assertEquals(AppleRouteGuidanceChange.NONE, change)
        assertEquals(before, store.current())
    }

    @Test
    fun `lane event requires position and status`() {
        val missingPosition = tlvs(tlv(0x0001, 2), tlv(0x0002, 0, 0))
        val missingStatus = tlvs(tlv(0x0000, 0, 1), tlv(0x0002, 0, 0))

        assertNull(
            AppleRouteGuidanceParser.parseLaneGuidanceUpdate(
                tlvs(tlv(0x0001, 1), tlvBytes(0x0002, missingPosition)),
            ),
        )
        assertNull(
            AppleRouteGuidanceParser.parseLaneGuidanceUpdate(
                tlvs(tlv(0x0001, 1), tlvBytes(0x0002, missingStatus)),
            ),
        )
    }

    @Test
    fun `lane position accepts one byte samples`() {
        val parsed = AppleRouteGuidanceParser.parseLaneGuidanceUpdate(
            laneEvent(1, lane(7, AppleLaneStatus.GOOD, intArrayOf(0), oneBytePosition = true)),
        )

        assertEquals(7, parsed!!.lanes.single().position)
    }

    @Test
    fun `unknown lane angle sentinel removes highlight`() {
        val parsed = AppleRouteGuidanceParser.parseLaneGuidanceUpdate(
            laneEvent(1, lane(0, AppleLaneStatus.PREFERRED, intArrayOf(-90, 0), highlight = 1000)),
        )

        assertNull(parsed!!.lanes.single().highlightAngleDegrees)
    }

    @Test
    fun `out of range lane angle rejects the complete event`() {
        val parsed = AppleRouteGuidanceParser.parseLaneGuidanceUpdate(
            laneEvent(1, lane(0, AppleLaneStatus.PREFERRED, intArrayOf(181))),
        )

        assertNull(parsed)
    }

    @Test
    fun `lane count and angle count enforce advertised bounds`() {
        val nineLanes = Array(AppleRouteGuidanceParser.MAX_LANES_PER_EVENT + 1) { position ->
            lane(position, AppleLaneStatus.GOOD, intArrayOf(0))
        }
        val seventeenAngles = IntArray(AppleRouteGuidanceParser.MAX_ANGLES_PER_LANE + 1) { 0 }

        assertNull(AppleRouteGuidanceParser.parseLaneGuidanceUpdate(laneEvent(1, *nineLanes)))
        assertNull(
            AppleRouteGuidanceParser.parseLaneGuidanceUpdate(
                laneEvent(1, lane(0, AppleLaneStatus.GOOD, seventeenAngles)),
            ),
        )
    }

    @Test
    fun `lane description is bounded by the advertised capability`() {
        val description = "x".repeat(AppleRouteGuidanceParser.MAX_LANE_DESCRIPTION_BYTES)
        assertNotNull(
            AppleRouteGuidanceParser.parseLaneGuidanceUpdate(
                tlvs(
                    tlv(0x0001, 0, 1),
                    tlvBytes(0x0002, lane(0, AppleLaneStatus.GOOD, intArrayOf(0))),
                    tlv(0x0003, *description.toByteArray().map { it.toInt() }.toIntArray()),
                ),
            ),
        )
        assertNull(
            AppleRouteGuidanceParser.parseLaneGuidanceUpdate(
                tlvs(
                    tlv(0x0001, 0, 1),
                    tlvBytes(0x0002, lane(0, AppleLaneStatus.GOOD, intArrayOf(0))),
                    tlv(0x0003, *(description + "x").toByteArray().map { it.toInt() }.toIntArray()),
                ),
            ),
        )
    }

    @Test
    fun `invalid UTF-8 rejects the complete lane event`() {
        assertNull(
            AppleRouteGuidanceParser.parseLaneGuidanceUpdate(
                tlvs(
                    tlv(0x0001, 0, 1),
                    tlvBytes(0x0002, lane(0, AppleLaneStatus.GOOD, intArrayOf(0))),
                    tlv(0x0003, 0xc3, 0x28, 0),
                ),
            ),
        )
    }

    @Test
    fun `out of range junction geometry rejects the complete maneuver`() {
        assertNull(
            AppleRouteGuidanceParser.parseManeuverUpdate(
                tlvs(
                    tlv(0x0001, 0, 5),
                    tlv(0x0003, 6),
                    tlv(0x000a, *signed16(181)),
                ),
            ),
        )
    }

    @Test
    fun `maneuver carries junction geometry and lane link`() {
        val maneuver = AppleRouteGuidanceParser.parseManeuverUpdate(
            tlvs(
                tlv(0x0001, 0, 5),
                tlv(0x0003, 6),
                tlv(0x0009, 0, 2),
                tlv(0x000a, *signed16(-90), *signed16(0), *signed16(45)),
                tlv(0x000b, *signed16(45)),
                tlv(0x000c, 0, 12),
            ),
        )

        assertEquals(12, maneuver!!.linkedLaneGuidanceIndex)
        assertEquals(2, maneuver.junction?.type)
        assertEquals(listOf(-90, 0, 45), maneuver.junction?.elementAngles)
        assertEquals(45, maneuver.junction?.exitAngle)
    }

    @Test
    fun `route end clears maneuver and lane caches`() {
        val store = populatedStore(laneIndex = 3)
        store.accept(
            AppleRouteGuidanceParser.LANE_GUIDANCE_INFORMATION,
            laneEvent(3, lane(0, AppleLaneStatus.GOOD, intArrayOf(0))),
        )

        assertEquals(
            AppleRouteGuidanceChange.CLEARED,
            store.accept(AppleRouteGuidanceParser.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x0001, 2))),
        )
        assertNull(store.current())
    }

    @Test
    fun `rerouting clears lane state without discarding the current maneuver`() {
        val store = populatedStore(laneIndex = 3)
        store.accept(
            AppleRouteGuidanceParser.LANE_GUIDANCE_INFORMATION,
            laneEvent(3, lane(0, AppleLaneStatus.PREFERRED, intArrayOf(0))),
        )
        assertNotNull(store.current()!!.laneGuidance)

        val change = store.accept(
            AppleRouteGuidanceParser.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x0001, AppleRouteState.REROUTING.wireValue)),
        )

        assertEquals(AppleRouteGuidanceChange.UPDATED, change)
        assertEquals(AppleRouteState.REROUTING, store.current()!!.routeState)
        assertNull(store.current()!!.laneGuidance)
    }

    @Test
    fun `brief empty maneuver list expires without deleting cached maneuver`() {
        var now = 0L
        val store = populatedStore(nanoTime = { now })

        store.accept(AppleRouteGuidanceParser.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x000d)))
        now = 2_000_000_000L
        assertNotNull(store.current())
        now = 3_100_000_000L
        assertNull(store.current())

        store.accept(
            AppleRouteGuidanceParser.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x0001, 1), tlv(0x000d, 0, 4)),
        )
        assertEquals(4, store.current()!!.maneuver.index)
    }

    @Test
    fun `stale route expires`() {
        var now = 0L
        val store = populatedStore(nanoTime = { now })

        now = AppleRouteGuidanceStore.DEFAULT_STALE_ROUTE_NANOS

        assertNull(store.current())
    }

    @Test
    fun `NoRoute can be retained only for replacement presentation policy`() {
        val store = populatedStore(keepAcrossNoRoute = true)
        val before = store.current()

        val change = store.accept(
            AppleRouteGuidanceParser.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x0001, 0)),
        )

        assertEquals(AppleRouteGuidanceChange.NONE, change)
        assertEquals(before, store.current())
    }

    @Test
    fun `retained NoRoute expires when the session stays active`() {
        var now = 0L
        val store = populatedStore(nanoTime = { now }, keepAcrossNoRoute = true)

        store.accept(AppleRouteGuidanceParser.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x0001, 0)))
        now = AppleRouteGuidanceStore.DEFAULT_NO_ROUTE_HIDE_NANOS - 1
        assertNotNull(store.current())
        now = AppleRouteGuidanceStore.DEFAULT_NO_ROUTE_HIDE_NANOS
        assertNull(store.current())
    }

    @Test
    fun `handoff clears the retained NoRoute expiry`() {
        var now = 0L
        val store = populatedStore(nanoTime = { now }, keepAcrossNoRoute = true)

        store.accept(AppleRouteGuidanceParser.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x0001, 0)))
        store.holdAcrossDrop()
        now = AppleRouteGuidanceStore.DEFAULT_NO_ROUTE_HIDE_NANOS * 2
        store.accept(AppleRouteGuidanceParser.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x0001, 0)))

        assertNotNull(store.current())
    }

    private fun populatedStore(
        laneIndex: Int? = null,
        nanoTime: () -> Long = { 0L },
        keepAcrossNoRoute: Boolean = false,
    ): AppleRouteGuidanceStore = AppleRouteGuidanceStore(
        nanoTime = nanoTime,
        keepAcrossNoRoute = keepAcrossNoRoute,
    ).also { store ->
        store.accept(
            AppleRouteGuidanceParser.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlvs(tlv(0x0001, 0, 4), tlv(0x0003, 2)),
        )
        store.accept(
            AppleRouteGuidanceParser.ROUTE_GUIDANCE_UPDATE,
            tlvs(
                tlv(0x0001, 1),
                tlv(0x000a, 0, 0, 0, 25),
                tlv(0x000d, 0, 4),
                *(laneIndex?.let { arrayOf(tlv(0x0010, 0, it), tlv(0x0012, 1)) } ?: emptyArray()),
            ),
        )
    }

    private fun laneEvent(eventIndex: Int, vararg lanes: ByteArray): ByteArray = tlvs(
        tlv(0x0001, 0, eventIndex),
        *lanes.map { tlvBytes(0x0002, it) }.toTypedArray(),
        tlv(0x0003, *utf8z("Keep lane")),
    )

    private fun lane(
        position: Int,
        status: AppleLaneStatus,
        angles: IntArray,
        highlight: Int? = null,
        oneBytePosition: Boolean = false,
    ): ByteArray {
        val values = ArrayList<ByteArray>()
        values += if (oneBytePosition) tlv(0x0000, position) else tlv(0x0000, 0, position)
        values += tlv(0x0001, status.wireValue)
        values += tlv(0x0002, *angles.flatMap { signed16(it).asList() }.toIntArray())
        if (highlight != null) values += tlv(0x0003, *signed16(highlight))
        return tlvs(*values.toTypedArray())
    }

    private fun signed16(value: Int): IntArray = intArrayOf((value ushr 8) and 0xff, value and 0xff)

    private fun u64(value: Long): IntArray = IntArray(8) { shift ->
        ((value ushr ((7 - shift) * 8)) and 0xff).toInt()
    }

    private fun utf8z(value: String): IntArray =
        (value.toByteArray(Charsets.UTF_8).map { it.toInt() and 0xff } + 0).toIntArray()

    private fun tlv(type: Int, vararg value: Int): ByteArray = ByteArrayOutputStream().apply {
        val length = value.size + 4
        write(length ushr 8)
        write(length)
        write(type ushr 8)
        write(type)
        value.forEach(::write)
    }.toByteArray()

    private fun tlvBytes(type: Int, value: ByteArray): ByteArray =
        tlv(type, *value.map { it.toInt() and 0xff }.toIntArray())

    private fun tlvs(vararg values: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        values.forEach(::write)
    }.toByteArray()
}

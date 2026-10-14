package com.lllhys.geeplay

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.navigation.apple.AppleLaneStatus
import com.shilapi.xcertplay.navigation.apple.AppleRouteGuidanceParser
import com.shilapi.xcertplay.navigation.apple.AppleRouteState
import java.io.ByteArrayOutputStream

/** Deterministic, privacy-safe iAP2 RouteGuidance frames for parked G636 validation. */
internal object CarPlayRouteGuidanceReplay {
    data class Phase(
        val name: String,
        val frames: List<Iap2Frame>,
        val expectedOemLaneIconIds: List<Int>,
    )

    fun phases(): List<Phase> = listOf(
        Phase(
            name = "left-and-straight-preferred",
            frames = listOf(
                maneuver(index = 1, type = 2, laneEvent = 7),
                laneEvent(
                    eventIndex = 7,
                    lane(position = 0, status = AppleLaneStatus.GOOD, angles = intArrayOf(-45)),
                    lane(
                        position = 1,
                        status = AppleLaneStatus.PREFERRED,
                        angles = intArrayOf(-45),
                        highlight = -45,
                    ),
                    lane(
                        position = 2,
                        status = AppleLaneStatus.PREFERRED,
                        angles = intArrayOf(0),
                        highlight = 0,
                    ),
                    lane(position = 3, status = AppleLaneStatus.GOOD, angles = intArrayOf(45)),
                ),
                route(maneuverIndex = 1, laneEvent = 7, laneShowing = true, nextMeters = 320),
            ),
            expectedOemLaneIconIds = listOf(1, 16, 15, 3),
        ),
        Phase(
            name = "straight-and-right-preferred",
            frames = listOf(
                maneuver(index = 2, type = 3, laneEvent = 8),
                laneEvent(
                    eventIndex = 8,
                    lane(position = 0, status = AppleLaneStatus.GOOD, angles = intArrayOf(0)),
                    lane(
                        position = 1,
                        status = AppleLaneStatus.PREFERRED,
                        angles = intArrayOf(-45, 0),
                        highlight = 0,
                    ),
                    lane(
                        position = 2,
                        status = AppleLaneStatus.PREFERRED,
                        angles = intArrayOf(45),
                        highlight = 45,
                    ),
                    lane(position = 3, status = AppleLaneStatus.GOOD, angles = intArrayOf(180)),
                ),
                route(maneuverIndex = 2, laneEvent = 8, laneShowing = true, nextMeters = 180),
            ),
            expectedOemLaneIconIds = listOf(0, 30, 18, 8),
        ),
        Phase(
            name = "captured-apple-maps-preferred-composite",
            frames = listOf(
                maneuver(index = 3, type = 2, laneEvent = 9),
                laneEvent(
                    eventIndex = 9,
                    lane(
                        position = 0,
                        status = AppleLaneStatus.PREFERRED,
                        angles = intArrayOf(),
                        highlight = 0,
                    ),
                    lane(
                        position = 1,
                        status = AppleLaneStatus.PREFERRED,
                        angles = intArrayOf(),
                        highlight = 0,
                    ),
                    lane(
                        position = 2,
                        status = AppleLaneStatus.PREFERRED,
                        angles = intArrayOf(90),
                        highlight = 0,
                    ),
                ),
                route(maneuverIndex = 3, laneEvent = 9, laneShowing = true, nextMeters = 220),
            ),
            expectedOemLaneIconIds = listOf(15, 15, 32),
        ),
        Phase(
            name = "lane-hidden-route-kept",
            frames = listOf(routeLaneVisibility(laneShowing = false)),
            expectedOemLaneIconIds = emptyList(),
        ),
        Phase(
            name = "first-pattern-restored",
            frames = listOf(route(maneuverIndex = 1, laneEvent = 7, laneShowing = true, nextMeters = 80)),
            expectedOemLaneIconIds = listOf(1, 16, 15, 3),
        ),
    )

    fun routeEnded(): Iap2Frame = frame(
        AppleRouteGuidanceParser.ROUTE_GUIDANCE_UPDATE,
        tlvs(tlv(0x0001, AppleRouteState.ARRIVED.wireValue)),
    )

    private fun maneuver(index: Int, type: Int, laneEvent: Int): Iap2Frame = frame(
        AppleRouteGuidanceParser.ROUTE_GUIDANCE_MANEUVER_UPDATE,
        tlvs(
            tlv(0x0001, 0, index),
            tlv(0x0003, type),
            tlv(0x0004, *utf8z("Replay route")),
            tlv(0x0008, 0),
            tlv(0x000c, 0, laneEvent),
        ),
    )

    private fun route(
        maneuverIndex: Int,
        laneEvent: Int,
        laneShowing: Boolean,
        nextMeters: Int,
    ): Iap2Frame = frame(
        AppleRouteGuidanceParser.ROUTE_GUIDANCE_UPDATE,
        tlvs(
            tlv(0x0001, AppleRouteState.ROUTE_SET.wireValue),
            tlv(0x0003, *utf8z("Replay road")),
            tlv(0x0006, *u64(600)),
            tlv(0x0007, *u32(4_200)),
            tlv(0x000a, *u32(nextMeters.toLong())),
            tlv(0x000d, 0, maneuverIndex),
            tlv(0x0010, 0, laneEvent),
            tlv(0x0011, 0, 1),
            tlv(0x0012, if (laneShowing) 1 else 0),
        ),
    )

    private fun routeLaneVisibility(laneShowing: Boolean): Iap2Frame = frame(
        AppleRouteGuidanceParser.ROUTE_GUIDANCE_UPDATE,
        tlvs(tlv(0x0012, if (laneShowing) 1 else 0)),
    )

    private fun laneEvent(eventIndex: Int, vararg lanes: ByteArray): Iap2Frame = frame(
        AppleRouteGuidanceParser.LANE_GUIDANCE_INFORMATION,
        tlvs(
            tlv(0x0001, 0, eventIndex),
            *lanes.map { tlvBytes(0x0002, it) }.toTypedArray(),
            tlv(0x0003, *utf8z("Replay lanes")),
        ),
    )

    private fun lane(
        position: Int,
        status: AppleLaneStatus,
        angles: IntArray,
        highlight: Int? = null,
    ): ByteArray {
        val values = ArrayList<ByteArray>()
        values += tlv(0x0000, 0, position)
        values += tlv(0x0001, status.wireValue)
        values += tlv(0x0002, *angles.flatMap { signed16(it).asList() }.toIntArray())
        if (highlight != null) values += tlv(0x0003, *signed16(highlight))
        return tlvs(*values.toTypedArray())
    }

    private fun frame(messageId: Int, payload: ByteArray) = Iap2Frame(messageId, payload)

    private fun signed16(value: Int): IntArray = intArrayOf((value ushr 8) and 0xff, value and 0xff)

    private fun u32(value: Long): IntArray = IntArray(4) { offset ->
        ((value ushr ((3 - offset) * 8)) and 0xff).toInt()
    }

    private fun u64(value: Long): IntArray = IntArray(8) { offset ->
        ((value ushr ((7 - offset) * 8)) and 0xff).toInt()
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

package com.shilapi.xcertplay.navigation.apple

import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal data class AppleRouteUpdate(
    val state: AppleRouteState?,
    val currentRoad: String?,
    val arrivalEpochSeconds: Long?,
    val hasArrivalEpochSeconds: Boolean,
    val remainingSeconds: Long?,
    val hasRemainingSeconds: Boolean,
    val remainingMeters: Long?,
    val hasRemainingMeters: Boolean,
    val nextDistanceMeters: Int?,
    val maneuverIndexes: List<Int>?,
    val laneGuidanceIndex: Int?,
    val hasLaneGuidanceIndex: Boolean,
    val laneGuidanceTotalCount: Int?,
    val laneGuidanceShowing: Boolean?,
)

/** Strict, bounded decoder for the Apple iAP2 RouteGuidance messages used by vehicle outputs. */
object AppleRouteGuidanceParser {
    const val ROUTE_GUIDANCE_UPDATE = 0x5201
    const val ROUTE_GUIDANCE_MANEUVER_UPDATE = 0x5202
    const val LANE_GUIDANCE_INFORMATION = 0x5204
    const val MAX_MANEUVER_CACHE_SIZE = 32
    const val MAX_LANE_EVENT_CACHE_SIZE = 8
    const val MAX_LANES_PER_EVENT = 8
    const val MAX_ANGLES_PER_LANE = 16
    const val MAX_LANE_DESCRIPTION_BYTES = 64
    const val MAX_JUNCTION_BRANCHES = 16
    const val MIN_GUIDANCE_ANGLE = -180
    const val MAX_GUIDANCE_ANGLE = 180
    const val UNKNOWN_LANE_ANGLE = 1000
    private const val MAX_ROUTE_TEXT_BYTES = 512

    fun isValidFrame(messageId: Int, payload: ByteArray): Boolean = when (messageId) {
        ROUTE_GUIDANCE_UPDATE -> parseRouteUpdate(payload) != null
        ROUTE_GUIDANCE_MANEUVER_UPDATE -> parseManeuverUpdate(payload) != null
        LANE_GUIDANCE_INFORMATION -> parseLaneGuidanceUpdate(payload) != null
        else -> false
    }

    /** Bounded, road-name-free summary suitable for persistent real-car diagnostics. */
    fun diagnosticSummary(messageId: Int, payload: ByteArray): String = when (messageId) {
        ROUTE_GUIDANCE_UPDATE -> parseRouteUpdate(payload)?.let { update ->
            "message=0x5201 bytes=${payload.size} valid=true state=${update.state?.name ?: "unchanged"} " +
                "maneuvers=${update.maneuverIndexes?.joinToString(",") ?: "unchanged"} " +
                "nextMeters=${update.nextDistanceMeters ?: "unchanged"} " +
                "remainingMeters=${if (update.hasRemainingMeters) update.remainingMeters ?: "none" else "unchanged"} " +
                "remainingSeconds=${if (update.hasRemainingSeconds) update.remainingSeconds ?: "none" else "unchanged"} " +
                "laneIndex=${if (update.hasLaneGuidanceIndex) update.laneGuidanceIndex ?: "none" else "unchanged"} " +
                "laneTotal=${update.laneGuidanceTotalCount ?: "unchanged"} " +
                "laneShowing=${update.laneGuidanceShowing ?: "unchanged"}"
        } ?: invalidDiagnostic(messageId, payload)
        ROUTE_GUIDANCE_MANEUVER_UPDATE -> parseManeuverUpdate(payload)?.let { maneuver ->
            "message=0x5202 bytes=${payload.size} valid=true index=${maneuver.index} type=${maneuver.type} " +
                "drivingSide=${maneuver.drivingSide} linkedLane=${maneuver.linkedLaneGuidanceIndex ?: "none"} " +
                "junctionType=${maneuver.junction?.type ?: "none"} " +
                "junctionAngles=${maneuver.junction?.elementAngles?.joinToString(",") ?: "none"} " +
                "junctionExit=${maneuver.junction?.exitAngle ?: "none"}"
        } ?: invalidDiagnostic(messageId, payload)
        LANE_GUIDANCE_INFORMATION -> parseLaneGuidanceUpdate(payload)?.let { guidance ->
            "message=0x5204 bytes=${payload.size} valid=true event=${guidance.eventIndex} " +
                "descriptionBytes=${guidance.description.toByteArray().size} lanes=${guidance.lanes.size} " +
                guidance.lanes.joinToString(prefix = "laneData=[", postfix = "]", separator = ";") { lane ->
                    "pos=${lane.position},status=${lane.status.name},angles=${lane.anglesDegrees.joinToString(",")}," +
                        "highlight=${lane.highlightAngleDegrees ?: "none"}"
                }
        } ?: invalidDiagnostic(messageId, payload)
        else -> "message=0x${messageId.toString(16)} bytes=${payload.size} supported=false"
    }

    private fun invalidDiagnostic(messageId: Int, payload: ByteArray): String =
        "message=0x${messageId.toString(16)} bytes=${payload.size} valid=false"

    internal fun parseRouteUpdate(payload: ByteArray): AppleRouteUpdate? = parseParameters(payload) { parameters ->
        var state: AppleRouteState? = null
        var currentRoad: String? = null
        var arrivalEpochSeconds: Long? = null
        var hasArrivalEpochSeconds = false
        var remainingSeconds: Long? = null
        var hasRemainingSeconds = false
        var remainingMeters: Long? = null
        var hasRemainingMeters = false
        var nextDistanceMeters: Int? = null
        var maneuverIndexes: List<Int>? = null
        var laneGuidanceIndex: Int? = null
        var hasLaneGuidanceIndex = false
        var laneGuidanceTotalCount: Int? = null
        var laneGuidanceShowing: Boolean? = null

        for (parameter in parameters.asList()) {
            val value = parameter.payload
            when (parameter.id) {
                0x0001 -> {
                    if (value.size != 1) return@parseParameters null
                    state = AppleRouteState.fromWire(u8(value)) ?: return@parseParameters null
                }
                0x0003 -> currentRoad = text(value, MAX_ROUTE_TEXT_BYTES) ?: return@parseParameters null
                0x0005 -> if (value.size == 8) {
                    hasArrivalEpochSeconds = true
                    arrivalEpochSeconds = u64(value).takeIf { it > 0 }
                } else return@parseParameters null
                0x0006 -> if (value.size == 8) {
                    hasRemainingSeconds = true
                    remainingSeconds = u64(value).takeIf { it >= 0 }
                } else return@parseParameters null
                0x0007 -> if (value.size == 4) {
                    hasRemainingMeters = true
                    remainingMeters = u32(value)
                } else return@parseParameters null
                0x000a -> if (value.size == 4) {
                    nextDistanceMeters = u32(value).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                } else return@parseParameters null
                0x000d -> maneuverIndexes = u16List(value) ?: return@parseParameters null
                0x0010 -> {
                    hasLaneGuidanceIndex = true
                    laneGuidanceIndex = unsignedScalar(value) ?: return@parseParameters null
                }
                0x0011 -> laneGuidanceTotalCount = unsignedScalar(value) ?: return@parseParameters null
                0x0012 -> {
                    if (value.size != 1) return@parseParameters null
                    laneGuidanceShowing = u8(value) != 0
                }
            }
        }
        AppleRouteUpdate(
            state = state,
            currentRoad = currentRoad,
            arrivalEpochSeconds = arrivalEpochSeconds,
            hasArrivalEpochSeconds = hasArrivalEpochSeconds,
            remainingSeconds = remainingSeconds,
            hasRemainingSeconds = hasRemainingSeconds,
            remainingMeters = remainingMeters,
            hasRemainingMeters = hasRemainingMeters,
            nextDistanceMeters = nextDistanceMeters,
            maneuverIndexes = maneuverIndexes,
            laneGuidanceIndex = laneGuidanceIndex,
            hasLaneGuidanceIndex = hasLaneGuidanceIndex,
            laneGuidanceTotalCount = laneGuidanceTotalCount,
            laneGuidanceShowing = laneGuidanceShowing,
        )
    }

    fun parseManeuverUpdate(payload: ByteArray): AppleManeuver? = parseParameters(payload) { parameters ->
        var index: Int? = null
        var type: Int? = null
        var drivingSide = 0
        var afterRoad = ""
        var linkedLaneGuidanceIndex: Int? = null
        var junctionType: Int? = null
        var junctionAngles = emptyList<Int>()
        var junctionExitAngle: Int? = null

        for (parameter in parameters.asList()) {
            val value = parameter.payload
            when (parameter.id) {
                0x0001 -> index = unsignedScalar(value) ?: return@parseParameters null
                0x0003 -> if (value.size == 1) type = u8(value) else return@parseParameters null
                0x0004 -> afterRoad = text(value, MAX_ROUTE_TEXT_BYTES) ?: return@parseParameters null
                0x0008 -> if (value.size == 1) drivingSide = u8(value) else return@parseParameters null
                0x0009 -> junctionType = unsignedScalar(value) ?: return@parseParameters null
                0x000a -> junctionAngles = signed16List(value, MAX_JUNCTION_BRANCHES)
                    ?.takeIf { angles -> angles.all { it in MIN_GUIDANCE_ANGLE..MAX_GUIDANCE_ANGLE } }
                    ?: return@parseParameters null
                0x000b -> junctionExitAngle = signed16(value)
                    ?.takeIf { it in MIN_GUIDANCE_ANGLE..MAX_GUIDANCE_ANGLE }
                    ?: return@parseParameters null
                0x000c -> linkedLaneGuidanceIndex = unsignedScalar(value) ?: return@parseParameters null
            }
        }
        val maneuverIndex = index ?: return@parseParameters null
        val maneuverType = type ?: return@parseParameters null
        AppleManeuver(
            index = maneuverIndex,
            type = maneuverType,
            drivingSide = drivingSide,
            afterRoad = afterRoad,
            linkedLaneGuidanceIndex = linkedLaneGuidanceIndex,
            junction = if (junctionType != null || junctionAngles.isNotEmpty() || junctionExitAngle != null) {
                AppleJunctionGuidance(junctionType, junctionAngles, junctionExitAngle)
            } else {
                null
            },
        )
    }

    fun parseLaneGuidanceUpdate(payload: ByteArray): AppleLaneGuidance? = parseParameters(payload) { parameters ->
        var eventIndex: Int? = null
        var description = ""
        val lanes = ArrayList<AppleLane>()
        for (parameter in parameters.asList()) {
            when (parameter.id) {
                0x0001 -> eventIndex = unsignedScalar(parameter.payload) ?: return@parseParameters null
                0x0002 -> {
                    val parsed = parseLaneInformation(parameter.payload) ?: return@parseParameters null
                    if (lanes.size + parsed.size > MAX_LANES_PER_EVENT) return@parseParameters null
                    lanes += parsed
                }
                0x0003 -> description = text(parameter.payload, MAX_LANE_DESCRIPTION_BYTES)
                    ?: return@parseParameters null
            }
        }
        AppleLaneGuidance(eventIndex ?: return@parseParameters null, description, lanes.toList())
    }

    private fun parseLaneInformation(payload: ByteArray): List<AppleLane>? = parseParameters(payload) { parameters ->
        data class LaneBuilder(
            val position: Int,
            var status: AppleLaneStatus? = null,
            val angles: MutableList<Int> = ArrayList(),
            var highlight: Int? = null,
        )

        val lanes = ArrayList<AppleLane>()
        var current: LaneBuilder? = null

        fun finish(): Boolean {
            val lane = current ?: return true
            val status = lane.status ?: return false
            if (lane.position !in 0..0xffff) return false
            // Apple Maps may omit the highlighted direction from LaneAngles and provide it only
            // as LaneAngleHighlight. It is still an allowed branch of the physical lane; retain it
            // in the normalized geometry so renderers can draw the complete composite arrow while
            // using highlightAngleDegrees to select the recommended branch.
            val normalizedAngles = lane.angles.toMutableList()
            lane.highlight?.takeIf { it !in normalizedAngles }?.let(normalizedAngles::add)
            lanes += AppleLane(lane.position, status, normalizedAngles, lane.highlight)
            return lanes.size <= MAX_LANES_PER_EVENT
        }

        for (parameter in parameters.asList()) {
            val value = parameter.payload
            when (parameter.id) {
                0x0000 -> {
                    if (!finish()) return@parseParameters null
                    current = LaneBuilder(unsignedScalar(value) ?: return@parseParameters null)
                }
                0x0001 -> {
                    val lane = current ?: return@parseParameters null
                    if (value.size != 1) return@parseParameters null
                    lane.status = AppleLaneStatus.fromWire(u8(value)) ?: return@parseParameters null
                }
                0x0002 -> {
                    val lane = current ?: return@parseParameters null
                    val next = signed16List(value, MAX_ANGLES_PER_LANE) ?: return@parseParameters null
                    if (lane.angles.size + next.size > MAX_ANGLES_PER_LANE) return@parseParameters null
                    if (next.any { it !in MIN_GUIDANCE_ANGLE..MAX_GUIDANCE_ANGLE }) return@parseParameters null
                    lane.angles += next
                }
                0x0003 -> {
                    val lane = current ?: return@parseParameters null
                    val angle = signed16(value) ?: return@parseParameters null
                    if (angle != UNKNOWN_LANE_ANGLE && angle !in MIN_GUIDANCE_ANGLE..MAX_GUIDANCE_ANGLE) {
                        return@parseParameters null
                    }
                    lane.highlight = angle.takeUnless { it == UNKNOWN_LANE_ANGLE }
                }
            }
        }
        if (!finish()) return@parseParameters null
        lanes.toList()
    }

    private inline fun <T> parseParameters(payload: ByteArray, parse: (Iap2ParameterList) -> T?): T? =
        try {
            parse(Iap2ParameterList.parse(payload))
        } catch (_: RuntimeException) {
            null
        } catch (_: java.io.IOException) {
            null
        }

    private fun text(value: ByteArray, maximumBytes: Int): String? {
        val terminator = value.indexOf(0)
        val end = if (terminator >= 0) terminator else value.size
        if (end > maximumBytes || value.size > maximumBytes + 1) return null
        if (terminator >= 0 && terminator != value.lastIndex) return null
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value, 0, end))
                .toString()
                .trim()
        }.getOrNull()
    }

    private fun unsignedScalar(value: ByteArray): Int? = when (value.size) {
        1 -> u8(value)
        2 -> u16(value)
        else -> null
    }

    private fun u16(value: ByteArray, offset: Int = 0): Int =
        ((value[offset].toInt() and 0xff) shl 8) or (value[offset + 1].toInt() and 0xff)

    private fun u8(value: ByteArray): Int = value[0].toInt() and 0xff

    private fun u32(value: ByteArray, offset: Int = 0): Long =
        ((value[offset].toLong() and 0xff) shl 24) or
            ((value[offset + 1].toLong() and 0xff) shl 16) or
            ((value[offset + 2].toLong() and 0xff) shl 8) or
            (value[offset + 3].toLong() and 0xff)

    private fun u64(value: ByteArray): Long = (u32(value) shl 32) or u32(value, 4)

    private fun u16List(value: ByteArray): List<Int>? {
        if (value.size % 2 != 0 || value.size / 2 > MAX_MANEUVER_CACHE_SIZE) return null
        return value.indices.step(2).map { u16(value, it) }
    }

    private fun signed16List(value: ByteArray, maximum: Int): List<Int>? {
        if (value.size % 2 != 0 || value.size / 2 > maximum) return null
        return value.indices.step(2).map { u16(value, it).toShort().toInt() }
    }

    private fun signed16(value: ByteArray): Int? =
        if (value.size == 2) u16(value).toShort().toInt() else null
}

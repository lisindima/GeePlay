package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.navigation.apple.AppleRouteGuidanceChange
import com.shilapi.xcertplay.navigation.apple.AppleRouteGuidanceParser
import com.shilapi.xcertplay.navigation.apple.AppleRouteGuidanceStore
import com.shilapi.xcertplay.navigation.apple.AppleJunctionGuidance

internal data class BydHudGuidance(
    val distanceMeters: Int,
    /** Native HUD arrow (field 28). */
    val maneuver: Int,
    /** Gaode maneuver code: selects the HUD icon (field 8). */
    val gaode: Int = 0,
    val road: String = "",
    val arrivalEpochSeconds: Long? = null,
)

internal data class BydAppleManeuver(
    val distanceMeters: Int,
    val type: Int,
    val drivingSide: Int,
    val road: String = "",
    val remainingSeconds: Long? = null,
    val remainingMeters: Long? = null,
    val arrivalEpochSeconds: Long? = null,
    val junction: AppleJunctionGuidance? = null,
)

internal enum class BydHudRouteChange {
    NONE,
    GUIDANCE,
    CLEAR,
}

/**
 * BYD compatibility view over the brand-neutral Apple RouteGuidance state.
 *
 * Vendor-neutral parsing and lifecycle live in [AppleRouteGuidanceStore]. This wrapper keeps the
 * established BYD output API and code mappings while those output drivers are migrated separately.
 */
internal class BydHudRouteState(
    nanoTime: () -> Long = System::nanoTime,
    staleRouteNs: Long = STALE_ROUTE_NS,
    emptyListHideNs: Long = EMPTY_LIST_HIDE_NS,
    /**
     * The dashboard overlay keeps the last instruction across a wireless session drop: the iPhone
     * often sends NoRouteSet (0) while the tunnel is tearing down, which is not a real arrival.
     * Arrived (2) still ends the route. While the session stays up, NoRouteSet is the driver
     * ending guidance, and the card goes after [noRouteHideNs] unless guidance comes back.
     */
    keepAcrossNoRoute: Boolean = false,
    noRouteHideNs: Long = NO_ROUTE_HIDE_NS,
) {
    // Kept mutable for the existing deterministic expiry tests and diagnostic tooling.
    private var nanoTime: () -> Long = nanoTime
    private val route = AppleRouteGuidanceStore(
        nanoTime = { this.nanoTime() },
        staleRouteNanos = staleRouteNs,
        emptyManeuverListNanos = emptyListHideNs,
        keepAcrossNoRoute = keepAcrossNoRoute,
        noRouteHideNanos = noRouteHideNs,
    )

    fun accept(messageId: Int, payload: ByteArray): BydHudRouteChange {
        val before = currentApple()
        val accepted = route.accept(messageId, payload)
        if (accepted == AppleRouteGuidanceChange.NONE) return BydHudRouteChange.NONE
        val after = currentApple()
        return when {
            before == after -> BydHudRouteChange.NONE
            after == null -> BydHudRouteChange.CLEAR
            else -> BydHudRouteChange.GUIDANCE
        }
    }

    fun current(): BydHudGuidance? {
        val current = route.current() ?: return null
        val gaode = BydManeuverCodes.gaode(current.maneuver.type, current.maneuver.drivingSide)
        return BydHudGuidance(
            distanceMeters = current.nextDistanceMeters,
            maneuver = BydManeuverCodes.hudArrow(gaode),
            gaode = gaode,
            road = current.maneuver.afterRoad.ifEmpty { current.currentRoad },
            arrivalEpochSeconds = current.arrivalEpochSeconds,
        )
    }

    fun currentApple(): BydAppleManeuver? {
        val current = route.current() ?: return null
        return BydAppleManeuver(
            distanceMeters = current.nextDistanceMeters,
            type = current.maneuver.type,
            drivingSide = current.maneuver.drivingSide,
            road = current.maneuver.afterRoad.ifEmpty { current.currentRoad },
            remainingSeconds = current.remainingSeconds,
            remainingMeters = current.remainingMeters,
            arrivalEpochSeconds = current.arrivalEpochSeconds,
            junction = current.maneuver.junction,
        )
    }

    fun clear(): Boolean = route.clear()

    fun holdAcrossDrop() = route.holdAcrossDrop()

    companion object {
        const val ROUTE_GUIDANCE_UPDATE = AppleRouteGuidanceParser.ROUTE_GUIDANCE_UPDATE
        const val ROUTE_GUIDANCE_MANEUVER_UPDATE = AppleRouteGuidanceParser.ROUTE_GUIDANCE_MANEUVER_UPDATE
        private const val STALE_ROUTE_NS = AppleRouteGuidanceStore.DEFAULT_STALE_ROUTE_NANOS
        private const val EMPTY_LIST_HIDE_NS = AppleRouteGuidanceStore.DEFAULT_EMPTY_LIST_NANOS
        private const val NO_ROUTE_HIDE_NS = AppleRouteGuidanceStore.DEFAULT_NO_ROUTE_HIDE_NANOS
    }
}

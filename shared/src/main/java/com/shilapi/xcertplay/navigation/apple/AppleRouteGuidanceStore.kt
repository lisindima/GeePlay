package com.shilapi.xcertplay.navigation.apple

/**
 * Per-iAP2-source RouteGuidance state. Callers must not share one instance between the wireless
 * Bluetooth bootstrap and the runtime tunnel because each link owns its own incremental caches.
 */
class AppleRouteGuidanceStore(
    private val generation: Long = 0,
    private val nanoTime: () -> Long = System::nanoTime,
    private val staleRouteNanos: Long = DEFAULT_STALE_ROUTE_NANOS,
    private val emptyManeuverListNanos: Long = DEFAULT_EMPTY_LIST_NANOS,
    private val keepAcrossNoRoute: Boolean = false,
) {
    private val maneuvers = boundedMap<Int, AppleManeuver>(AppleRouteGuidanceParser.MAX_MANEUVER_CACHE_SIZE)
    private val laneEvents = boundedMap<Int, AppleLaneGuidance>(AppleRouteGuidanceParser.MAX_LANE_EVENT_CACHE_SIZE)
    private var routeState = AppleRouteState.NO_ROUTE_SET
    private var routeActive = false
    private var activeManeuverIndex: Int? = null
    private var currentRoad = ""
    private var nextDistanceMeters = 0
    private var arrivalEpochSeconds: Long? = null
    private var remainingSeconds: Long? = null
    private var remainingMeters: Long? = null
    private var laneGuidanceIndex: Int? = null
    private var laneGuidanceShowing = false
    private var laneGuidanceTotalCount: Int? = null
    private var emptyListSinceNanos: Long? = null
    private var lastRouteUpdateNanos: Long? = null
    private var revision = 0L

    @Synchronized
    fun accept(messageId: Int, payload: ByteArray): AppleRouteGuidanceChange {
        val before = visibleState()
        val accepted = when (messageId) {
            AppleRouteGuidanceParser.ROUTE_GUIDANCE_UPDATE -> {
                val update = AppleRouteGuidanceParser.parseRouteUpdate(payload) ?: return AppleRouteGuidanceChange.NONE
                applyRouteUpdate(update)
                true
            }
            AppleRouteGuidanceParser.ROUTE_GUIDANCE_MANEUVER_UPDATE -> {
                val maneuver = AppleRouteGuidanceParser.parseManeuverUpdate(payload)
                    ?: return AppleRouteGuidanceChange.NONE
                maneuvers[maneuver.index] = maneuver
                true
            }
            AppleRouteGuidanceParser.LANE_GUIDANCE_INFORMATION -> {
                val lane = AppleRouteGuidanceParser.parseLaneGuidanceUpdate(payload)
                    ?: return AppleRouteGuidanceChange.NONE
                laneEvents[lane.eventIndex] = lane
                true
            }
            else -> false
        }
        if (!accepted) return AppleRouteGuidanceChange.NONE
        val after = visibleState()
        if (after == before) return AppleRouteGuidanceChange.NONE
        revision += 1
        return if (after == null) AppleRouteGuidanceChange.CLEARED else AppleRouteGuidanceChange.UPDATED
    }

    @Synchronized
    fun current(): AppleNavigationSnapshot? {
        val visible = visibleState() ?: return null
        return AppleNavigationSnapshot(
            generation = generation,
            revision = revision,
            routeState = visible.routeState,
            currentRoad = visible.currentRoad,
            maneuver = visible.maneuver,
            nextDistanceMeters = visible.nextDistanceMeters,
            remainingMeters = visible.remainingMeters,
            remainingSeconds = visible.remainingSeconds,
            arrivalEpochSeconds = visible.arrivalEpochSeconds,
            laneGuidance = visible.laneGuidance,
            updatedAtNanos = lastRouteUpdateNanos ?: 0L,
        )
    }

    @Synchronized
    fun clear(): Boolean {
        val hadState = routeActive || maneuvers.isNotEmpty() || laneEvents.isNotEmpty()
        clearState()
        if (hadState) revision += 1
        return hadState
    }

    private fun applyRouteUpdate(update: AppleRouteUpdate) {
        if (keepAcrossNoRoute && update.state == AppleRouteState.NO_ROUTE_SET) return
        lastRouteUpdateNanos = nanoTime()
        if (update.state == AppleRouteState.NO_ROUTE_SET || update.state == AppleRouteState.ARRIVED) {
            clearState()
            return
        }
        update.state?.let {
            routeState = it
            routeActive = true
            if (it == AppleRouteState.REROUTING) {
                laneEvents.clear()
                laneGuidanceIndex = null
                laneGuidanceShowing = false
                laneGuidanceTotalCount = null
            }
        }
        update.currentRoad?.let { currentRoad = it }
        if (update.hasArrivalEpochSeconds) arrivalEpochSeconds = update.arrivalEpochSeconds
        if (update.hasRemainingSeconds) remainingSeconds = update.remainingSeconds
        if (update.hasRemainingMeters) remainingMeters = update.remainingMeters
        update.nextDistanceMeters?.let { nextDistanceMeters = it.coerceAtLeast(0) }
        update.maneuverIndexes?.let { indexes ->
            if (indexes.isEmpty()) {
                if (emptyListSinceNanos == null) emptyListSinceNanos = nanoTime()
            } else {
                activeManeuverIndex = indexes.first()
                emptyListSinceNanos = null
                routeActive = true
                if (routeState == AppleRouteState.NO_ROUTE_SET) routeState = AppleRouteState.ROUTE_SET
            }
        }
        if (update.hasLaneGuidanceIndex) laneGuidanceIndex = update.laneGuidanceIndex
        update.laneGuidanceShowing?.let { laneGuidanceShowing = it }
        update.laneGuidanceTotalCount?.let { count ->
            laneGuidanceTotalCount = count
            if (count == 0) laneEvents.clear()
        }
    }

    private fun clearState() {
        routeState = AppleRouteState.NO_ROUTE_SET
        routeActive = false
        activeManeuverIndex = null
        currentRoad = ""
        nextDistanceMeters = 0
        arrivalEpochSeconds = null
        remainingSeconds = null
        remainingMeters = null
        laneGuidanceIndex = null
        laneGuidanceShowing = false
        laneGuidanceTotalCount = null
        emptyListSinceNanos = null
        lastRouteUpdateNanos = null
        maneuvers.clear()
        laneEvents.clear()
    }

    private fun visibleState(): VisibleState? {
        if (!routeActive) return null
        val routeUpdated = lastRouteUpdateNanos ?: return null
        if (nanoTime() - routeUpdated >= staleRouteNanos) return null
        val emptySince = emptyListSinceNanos
        if (emptySince != null && nanoTime() - emptySince >= emptyManeuverListNanos) return null
        val maneuver = activeManeuverIndex?.let(maneuvers::get) ?: return null
        return VisibleState(
            routeState = routeState,
            currentRoad = currentRoad,
            maneuver = maneuver,
            nextDistanceMeters = nextDistanceMeters,
            remainingMeters = remainingMeters,
            remainingSeconds = remainingSeconds,
            arrivalEpochSeconds = arrivalEpochSeconds,
            laneGuidance = if (laneGuidanceShowing) laneGuidanceIndex?.let(laneEvents::get) else null,
        )
    }

    private data class VisibleState(
        val routeState: AppleRouteState,
        val currentRoad: String,
        val maneuver: AppleManeuver,
        val nextDistanceMeters: Int,
        val remainingMeters: Long?,
        val remainingSeconds: Long?,
        val arrivalEpochSeconds: Long?,
        val laneGuidance: AppleLaneGuidance?,
    )

    companion object {
        const val DEFAULT_STALE_ROUTE_NANOS = 30_000_000_000L
        const val DEFAULT_EMPTY_LIST_NANOS = 3_000_000_000L

        private fun <K, V> boundedMap(maximumSize: Int): LinkedHashMap<K, V> =
            object : LinkedHashMap<K, V>(maximumSize + 1, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
                    size > maximumSize
            }
    }
}

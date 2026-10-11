package com.shilapi.xcertplay.navigation.apple

enum class AppleRouteState(val wireValue: Int) {
    NO_ROUTE_SET(0),
    ROUTE_SET(1),
    ARRIVED(2),
    LOADING(3),
    LOCATING(4),
    REROUTING(5),
    PROCEED_TO_ROUTE(6);

    companion object {
        fun fromWire(value: Int): AppleRouteState? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class AppleLaneStatus(val wireValue: Int) {
    NOT_GOOD(0),
    GOOD(1),
    PREFERRED(2);

    companion object {
        fun fromWire(value: Int): AppleLaneStatus? = entries.firstOrNull { it.wireValue == value }
    }
}

data class AppleJunctionGuidance(
    val type: Int?,
    val elementAngles: List<Int>,
    val exitAngle: Int?,
)

data class AppleManeuver(
    val index: Int,
    val type: Int,
    val drivingSide: Int,
    val afterRoad: String,
    val linkedLaneGuidanceIndex: Int?,
    val junction: AppleJunctionGuidance?,
)

data class AppleLane(
    val position: Int,
    val status: AppleLaneStatus,
    val anglesDegrees: List<Int>,
    val highlightAngleDegrees: Int?,
)

data class AppleLaneGuidance(
    val eventIndex: Int,
    val description: String,
    val lanes: List<AppleLane>,
)

data class AppleNavigationSnapshot(
    val generation: Long,
    val revision: Long,
    val routeState: AppleRouteState,
    val currentRoad: String,
    val maneuver: AppleManeuver,
    val nextDistanceMeters: Int,
    val remainingMeters: Long?,
    val remainingSeconds: Long?,
    val arrivalEpochSeconds: Long?,
    val laneGuidance: AppleLaneGuidance?,
    val updatedAtNanos: Long,
)

enum class AppleRouteGuidanceChange {
    NONE,
    UPDATED,
    CLEARED,
}

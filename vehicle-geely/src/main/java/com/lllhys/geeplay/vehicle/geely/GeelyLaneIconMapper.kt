package com.lllhys.geeplay.vehicle.geely

import com.shilapi.xcertplay.navigation.apple.AppleLane
import com.shilapi.xcertplay.navigation.apple.AppleLaneStatus

/** Maps CarPlay lane angles to the lane icon IDs used by the G636 factory map. */
object GeelyLaneIconMapper {
    fun iconId(lane: AppleLane): Int {
        val back = action(lane.anglesDegrees)
        if (lane.status != AppleLaneStatus.PREFERRED) return back
        val selected = lane.highlightAngleDegrees?.let { action(listOf(it)) } ?: back
        return RECOMMENDED[back to selected] ?: back
    }

    private fun action(angles: List<Int>): Int {
        var mask = 0
        angles.ifEmpty { listOf(0) }.forEach { angle ->
            mask = mask or when {
                angle <= -135 -> LEFT_U_TURN
                angle < -15 -> LEFT
                angle < 15 -> STRAIGHT
                angle < 135 -> RIGHT
                else -> RIGHT_U_TURN
            }
        }
        return ACTIONS[mask] ?: STRAIGHT_ICON
    }

    private const val STRAIGHT = 1
    private const val LEFT = 1 shl 1
    private const val RIGHT = 1 shl 2
    private const val LEFT_U_TURN = 1 shl 3
    private const val RIGHT_U_TURN = 1 shl 4
    private const val STRAIGHT_ICON = 0

    private val ACTIONS = mapOf(
        STRAIGHT to 0,
        LEFT to 1,
        (STRAIGHT or LEFT) to 2,
        RIGHT to 3,
        (STRAIGHT or RIGHT) to 4,
        (LEFT or RIGHT) to 5,
        LEFT_U_TURN to 6,
        (STRAIGHT or LEFT or RIGHT) to 7,
        RIGHT_U_TURN to 8,
        (STRAIGHT or LEFT_U_TURN) to 9,
        (STRAIGHT or RIGHT_U_TURN) to 10,
    )

    // Captured from the factory map's LaneInfoResUtil and confirmed on a physical G636.
    private val RECOMMENDED = mapOf(
        (0 to 0) to 15,
        (1 to 1) to 16,
        (2 to 0) to 30,
        (2 to 1) to 31,
        (3 to 3) to 18,
        (4 to 0) to 32,
        (4 to 3) to 33,
        (5 to 5) to 20,
        (7 to 0) to 36,
        (7 to 1) to 37,
        (7 to 3) to 38,
        (8 to 8) to 23,
        (9 to 0) to 39,
        (10 to 0) to 41,
        (10 to 8) to 42,
    )
}

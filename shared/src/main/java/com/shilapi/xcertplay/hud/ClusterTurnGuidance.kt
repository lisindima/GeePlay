package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.navigation.apple.AppleJunctionGuidance

/** Next-turn instruction for the dashboard overlay. Icons use the AMap NEW_ICON vocabulary. */
data class ClusterTurnGuidance(
    val icon: Int,
    val roundaboutExit: Int,
    val distanceMeters: Int,
    val road: String,
    /** Route-level arrival/duration/distance for the info strip below the card. */
    val arrivalEpochSeconds: Long? = null,
    val remainingSeconds: Long? = null,
    val remainingMeters: Long? = null,
    /** Semantic junction branches from 0x5202; this is not an OEM raster cross image. */
    val junction: AppleJunctionGuidance? = null,
) {
    companion object {
        internal fun from(frame: BydClusterFrame): ClusterTurnGuidance {
            return ClusterTurnGuidance(frame.icon, frame.roundaboutExit, frame.distanceMeters, frame.road)
        }
    }
}

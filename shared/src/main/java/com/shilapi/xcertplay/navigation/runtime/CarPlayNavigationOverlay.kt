package com.shilapi.xcertplay.navigation.runtime

import com.shilapi.xcertplay.hud.BydAppleManeuver
import com.shilapi.xcertplay.hud.BydClusterFrame
import com.shilapi.xcertplay.hud.ClusterTurnGuidance

/** Brand-neutral publication endpoint for DiPlay's own dashboard turn card. */
object CarPlayNavigationOverlay : NavigationOutput {
    private val lock = Any()
    private var current: ClusterTurnGuidance? = null
    @Volatile private var listener: ((ClusterTurnGuidance?) -> Unit)? = null

    fun setListener(next: ((ClusterTurnGuidance?) -> Unit)?) {
        val snapshot = synchronized(lock) {
            listener = next
            current
        }
        next?.invoke(snapshot)
    }

    override fun publish(publication: NavigationPublication) {
        val snapshot = publication.snapshot
        val maneuver = snapshot.maneuver
        val guidance = ClusterTurnGuidance.from(
            BydClusterFrame.from(
                BydAppleManeuver(
                    distanceMeters = snapshot.nextDistanceMeters,
                    type = maneuver.type,
                    drivingSide = maneuver.drivingSide,
                    road = maneuver.afterRoad.ifEmpty { snapshot.currentRoad },
                    remainingSeconds = snapshot.remainingSeconds,
                    remainingMeters = snapshot.remainingMeters,
                    arrivalEpochSeconds = snapshot.arrivalEpochSeconds,
                    junction = maneuver.junction,
                ),
            ),
        ).copy(
            arrivalEpochSeconds = snapshot.arrivalEpochSeconds,
            remainingSeconds = snapshot.remainingSeconds,
            remainingMeters = snapshot.remainingMeters,
            junction = maneuver.junction,
        )
        val callback = synchronized(lock) {
            if (current == guidance) return
            current = guidance
            listener
        }
        callback?.invoke(guidance)
    }

    override fun clear(clear: NavigationClear) {
        val callback = synchronized(lock) {
            if (current == null) return
            current = null
            listener
        }
        callback?.invoke(null)
    }

    override fun close() {
        val callback = synchronized(lock) {
            current = null
            listener
        }
        callback?.invoke(null)
    }
}

package com.lllhys.geeplay.vehicle.geely

import android.os.Build
import com.shilapi.xcertplay.vehicle.VehicleClusterDisplayPolicy
import com.shilapi.xcertplay.vehicle.VehicleClusterDisplayTarget
import com.shilapi.xcertplay.vehicle.VehicleDisplayCandidate

data class GeelyClusterDisplayProfile(
    val id: String,
    val fingerprint: Regex,
    val displayName: String,
    val width: Int,
    val height: Int,
)

/** Selects only displays backed by an explicitly validated firmware profile. */
class GeelyClusterDisplayPolicy(
    private val fingerprint: String = Build.FINGERPRINT,
    private val profiles: List<GeelyClusterDisplayProfile> = VALIDATED_PROFILES,
    private val enabled: Boolean = true,
) : VehicleClusterDisplayPolicy {
    override fun select(candidates: List<VehicleDisplayCandidate>): VehicleClusterDisplayTarget? {
        if (!enabled) return null
        val matches = profiles.filter { it.fingerprint.matches(fingerprint) }.mapNotNull { profile ->
            candidates.singleOrNull {
                it.name == profile.displayName && it.width == profile.width && it.height == profile.height
            }?.let { profile to it }
        }
        if (matches.size != 1) return null
        val (profile, candidate) = matches.single()
        return VehicleClusterDisplayTarget(candidate.displayId, profile.id)
    }

    fun hasValidatedFirmware(): Boolean = profiles.any { it.fingerprint.matches(fingerprint) }

    companion object {
        /** Populated only after a real-car display route and cleanup have been recorded. */
        val VALIDATED_PROFILES: List<GeelyClusterDisplayProfile> = emptyList()
    }
}

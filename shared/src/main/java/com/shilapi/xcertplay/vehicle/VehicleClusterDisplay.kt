package com.shilapi.xcertplay.vehicle

data class VehicleDisplayCandidate(
    val displayId: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val flags: Int,
)

data class VehicleClusterDisplayTarget(
    val displayId: Int,
    val profileId: String,
)

fun interface VehicleClusterDisplayPolicy {
    fun select(candidates: List<VehicleDisplayCandidate>): VehicleClusterDisplayTarget?
}

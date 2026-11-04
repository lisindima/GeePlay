package com.lllhys.geeplay.vehicle.geely

/** Explicit, opt-in endpoint for GeePlay's own QNX window; AtlasDim owns its own ports. */
data class GeelyQnxWindowConfig(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val enabled: Boolean = false,
) {
    init {
        require(port in 1024..65535) { "QNX window port must be between 1024 and 65535" }
        require(port !in ATLAS_DIM_PORTS) { "AtlasDim ports are reserved" }
        require(!enabled || host.isNotBlank()) { "An enabled QNX window needs a host" }
    }

    companion object {
        const val DEFAULT_PORT = 53281
        val ATLAS_DIM_PORTS = setOf(50210, 50211, 50212)
    }
}

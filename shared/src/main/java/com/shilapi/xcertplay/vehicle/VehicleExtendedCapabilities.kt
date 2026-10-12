package com.shilapi.xcertplay.vehicle

import java.io.Closeable
import java.util.concurrent.CompletableFuture

enum class VehicleWheelPosition { FRONT_LEFT, FRONT_RIGHT, REAR_LEFT, REAR_RIGHT }

data class VehicleTyreReading(
    val pressureKpa: Double? = null,
    val temperatureC: Double? = null,
    val observedAtElapsedMillis: Long,
)

data class VehicleTyreTelemetrySnapshot(
    val wheels: Map<VehicleWheelPosition, VehicleTyreReading>,
)

fun interface VehicleParkStateSource {
    /** true=P, false=a known non-P gear, null=unknown/stale. Safety consumers must fail closed on null. */
    fun parked(maxAgeMillis: Long): Boolean?
}

interface VehicleTyreTelemetrySource {
    fun start()
    fun snapshot(maxAgeMillis: Long): VehicleTyreTelemetrySnapshot?
    fun stop() = Unit
}

fun interface ClusterMapVisibilitySource {
    /** true=OEM cluster map visible, false=hidden, null=unknown. Unknown must keep the CarPlay stream on. */
    fun mapShownOnCluster(): Boolean?
}

data class InstrumentNowPlayingSnapshot(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val playing: Boolean,
)

data class InstrumentCallSnapshot(
    val displayName: String,
    val phase: Phase,
) {
    enum class Phase { RINGING, DIALING, ACTIVE, ENDED }
}

interface InstrumentMediaOutput : Closeable {
    fun publish(snapshot: InstrumentNowPlayingSnapshot)
    fun clear()
    override fun close() = clear()

    companion object {
        val NONE = object : InstrumentMediaOutput {
            override fun publish(snapshot: InstrumentNowPlayingSnapshot) = Unit
            override fun clear() = Unit
        }
    }
}

interface InstrumentCallOutput : Closeable {
    fun publish(snapshot: InstrumentCallSnapshot)
    fun clear()
    override fun close() = clear()

    companion object {
        val NONE = object : InstrumentCallOutput {
            override fun publish(snapshot: InstrumentCallSnapshot) = Unit
            override fun clear() = Unit
        }
    }
}

enum class VehicleSystemAction {
    GRANT_WRITE_SETTINGS,
    GRANT_OVERLAY,
    ALLOW_BACKGROUND,
    ALLOW_DOZE,
}

data class VehicleSystemActionResult(
    val accepted: Boolean,
    val status: String,
    val detail: String = "",
)

fun interface VehicleSystemActionPort {
    /** Explicit user action only; implementations must never run this from startup/session callbacks. */
    fun execute(action: VehicleSystemAction): CompletableFuture<VehicleSystemActionResult>
}

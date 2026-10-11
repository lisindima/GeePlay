package com.shilapi.xcertplay.vehicle

import com.shilapi.xcertplay.transport.EvChargingConnectors
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Vehicle-data inputs frozen for one CarPlay controller generation.
 *
 * Providers must be nonblocking: the iAP2 control loop calls them directly. [close] retires only
 * this session's demand and is safe to call repeatedly.
 */
class CarPlayVehicleDataSession(
    val statusProvider: VehicleStatusProvider? = null,
    val speedSource: VehicleSpeedSource? = null,
    val chargingConnectors: EvChargingConnectors = EvChargingConnectors.CCS2_TYPE2,
    private val release: () -> Unit = {},
) : Closeable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

/** Product adapter for the vehicle data that the accessory can advertise to CarPlay. */
interface CarPlayVehicleDataAdapter : Closeable {
    /** Starts or reconciles asynchronous discovery. Never blocks the caller. */
    fun prepare()

    /** Returns a nonblocking snapshot of the capabilities available for a new CarPlay session. */
    fun createSession(): CarPlayVehicleDataSession

    /** Sanitized state for the existing diagnostics surface. */
    fun diagnostics(): String = "vehicle data diagnostics unavailable"

    override fun close() = Unit
}

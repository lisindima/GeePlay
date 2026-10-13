package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.vehicle.VehicleDataDiagnostics
import kotlin.math.min
import kotlin.math.roundToLong

/** iAP2 VehicleInformation.EngineType wire values. The parameter repeats for multi-powertrain cars. */
enum class VehicleEngineType(internal val wireValue: Int) {
    GASOLINE(0),
    DIESEL(1),
    ELECTRIC(2),
    CNG(3),
}

/** Range information for one declared [VehicleEngineType]. */
data class VehiclePowertrainRange(
    val rangeKm: Int,
    val rangeWarning: Boolean? = null,
    val maxRangeKm: Int? = null,
)

/** One vehicle reading, including generic and powertrain-specific ranges, for VehicleStatusUpdate. */
data class VehicleStatusSnapshot(
    val rangeKm: Int,
    val rangeWarning: Boolean,
    val batteryPercent: Double,
    val currentChargeWh: Long?,
    val maxChargeWh: Long?,
    val maxRangeKm: Int,
    val charging: Boolean = false,
    /** Null keeps the legacy pure-EV mapping based on [rangeKm], [rangeWarning], and [maxRangeKm]. */
    val powertrainRanges: Map<VehicleEngineType, VehiclePowertrainRange>? = null,
) {
    internal fun resolvedPowertrainRanges(): Map<VehicleEngineType, VehiclePowertrainRange> =
        powertrainRanges?.takeIf { it.isNotEmpty() } ?: mapOf(
            VehicleEngineType.ELECTRIC to VehiclePowertrainRange(rangeKm, rangeWarning, maxRangeKm),
        )
}

/** Supplies the vehicle's status for CarPlay; null while no reading is available. */
fun interface VehicleStatusProvider {
    fun snapshot(): VehicleStatusSnapshot?
}

/** The charging inlets the car declares, as iAP2 SupportedChargingConnectors values. */
enum class EvChargingConnectors(internal vararg val wireValues: Int) {
    CCS2_TYPE2(1, 4), // CCS2 (DC) and Mennekes / Type 2 (AC), as on European BYD models
    GB_T(5, 6), // GB/T DC and AC, as on Chinese-market models
    CCS1_J1772(0, 2), // CCS1 (DC) and J1772 (AC)
}

/**
 * The identification to send: vehicle status is declared only while [provider] has a reading, so
 * the iPhone is never offered data that the accessory cannot report (ADB off, not approved, or
 * unavailable properties). The powertrain set is frozen for this connection. Never blocks.
 */
fun Iap2IdentificationConfig.withVehicleStatusFrom(provider: VehicleStatusProvider?): Iap2IdentificationConfig =
    if (!vehicleStatusEnabled) {
        this
    } else {
        provider?.snapshot()?.let { snapshot ->
            copy(vehicleEngineTypes = snapshot.resolvedPowertrainRanges().keys)
        } ?: copy(vehicleStatusEnabled = false)
    }

/**
 * iAP2 vehicle plane: identification declares the vehicle's engine types,
 * charging connectors and which status fields it reports; the phone then subscribes with
 * StartVehicleStatusUpdates (0xA100) and the accessory answers with VehicleStatusUpdate (0xA101).
 * Parameter ids follow Apple's iAP2 message catalog.
 */
object Iap2VehicleStatus {
    const val START_VEHICLE_STATUS_UPDATES = 0xA100
    const val VEHICLE_STATUS_UPDATE = 0xA101
    const val STOP_VEHICLE_STATUS_UPDATES = 0xA102
    const val UPDATE_INTERVAL_MILLIS = 30_000L

    /** IdentificationInformation params 20 (VehicleInformation) and 21 (VehicleStatus). */
    fun Iap2BodyBuilder.vehicleComponents(
        name: String,
        engineTypes: Set<VehicleEngineType>,
        connectors: EvChargingConnectors,
    ) {
        group(20) {
            u16(0, 3) // Identifier
            string(1, name) // Name
            engineTypes.sortedBy(VehicleEngineType::wireValue).forEach { u8(2, it.wireValue) } // EngineType
            string(6, name) // DisplayName
            string(8, name) // MapsDisplayName
            if (VehicleEngineType.ELECTRIC in engineTypes) {
                for (connector in connectors.wireValues) u8(11, connector) // SupportedChargingConnectors
            }
        }
        group(21) {
            u16(0, 4) // Identifier
            string(1, name) // Name
            void(3) // Range
            void(6) // RangeWarning
            for (engineType in engineTypes.sortedBy(VehicleEngineType::wireValue)) {
                POWERTRAIN_PARAMETERS.getValue(engineType).let { parameters ->
                    void(parameters.range) // Range for this powertrain
                    void(parameters.rangeWarning) // Low-range warning for this powertrain
                    void(parameters.maxRange) // Maximum range for this powertrain
                }
            }
            if (VehicleEngineType.ELECTRIC in engineTypes) void(21) // ElectricChargeInfo
        }
    }

    fun update(status: VehicleStatusSnapshot): Iap2Frame = Iap2Messages.buildRaw(VEHICLE_STATUS_UPDATE) {
        val powertrainRanges = status.resolvedPowertrainRanges()
        u16(3, status.rangeKm.coerceIn(0, 0xffff)) // Range, km
        bool(6, status.rangeWarning) // RangeWarning
        powertrainRanges
            .toSortedMap(compareBy(VehicleEngineType::wireValue))
            .forEach { (engineType, range) ->
                val parameters = POWERTRAIN_PARAMETERS.getValue(engineType)
                u16(parameters.range, range.rangeKm.coerceIn(0, 0xffff))
                range.rangeWarning?.let { bool(parameters.rangeWarning, it) }
                range.maxRangeKm?.let { u16(parameters.maxRange, it.coerceIn(0, 0xffff)) }
            }
        if (VehicleEngineType.ELECTRIC in powertrainRanges) {
            // Unknown energy is omitted, never represented as an empty battery or a zero capacity.
            if (status.currentChargeWh != null || status.maxChargeWh != null) u32(21, 0L) // MinBatteryCharge, Wh
            status.currentChargeWh?.let { u32(22, it.coerceAtLeast(0)) } // CurrentBatteryCharge, Wh
            status.maxChargeWh?.let { u32(23, it.coerceAtLeast(0)) } // MaxBatteryCharge, Wh
            u32(24, (status.batteryPercent.coerceIn(0.0, 100.0) * 1000).roundToLong()) // DisplayedBatteryPercentage, % x 1000
            bool(25, status.charging) // isCharging
        }
    }

    private data class PowertrainParameters(
        val range: Int,
        val rangeWarning: Int,
        val maxRange: Int,
    )

    private val POWERTRAIN_PARAMETERS = mapOf(
        VehicleEngineType.GASOLINE to PowertrainParameters(range = 9, rangeWarning = 13, maxRange = 31),
        VehicleEngineType.DIESEL to PowertrainParameters(range = 10, rangeWarning = 14, maxRange = 32),
        VehicleEngineType.ELECTRIC to PowertrainParameters(range = 11, rangeWarning = 15, maxRange = 30),
        VehicleEngineType.CNG to PowertrainParameters(range = 12, rangeWarning = 16, maxRange = 33),
    )
}

/** Serves the phone's vehicle-status subscription inside an iAP2 control loop. */
internal class Iap2VehicleStatusReporter(
    private val provider: VehicleStatusProvider?,
    private val onProgress: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var active = false
    private var lastSentNanos = 0L

    /** Handles 0xA100/0xA102; returns false for any other message. */
    fun handle(frame: Iap2Frame, send: (Iap2Frame) -> Unit): Boolean = when (frame.messageId) {
        Iap2VehicleStatus.START_VEHICLE_STATUS_UPDATES -> {
            active = provider != null
            onProgress(
                VehicleDataDiagnostics.record(
                    "iap2-status-rx",
                    "messageId=0xa100 action=start provider=${provider != null}",
                ),
            )
            if (active) sendNow(send)
            true
        }
        Iap2VehicleStatus.STOP_VEHICLE_STATUS_UPDATES -> {
            active = false
            onProgress(
                VehicleDataDiagnostics.record(
                    "iap2-status-rx",
                    "messageId=0xa102 action=stop",
                ),
            )
            true
        }
        else -> false
    }

    /** Sends the periodic update when it is due. */
    fun tick(send: (Iap2Frame) -> Unit) {
        if (active && nanoTime() - lastSentNanos >= Iap2VehicleStatus.UPDATE_INTERVAL_MILLIS * 1_000_000) sendNow(send)
    }

    fun pollTimeout(remainingMillis: Long): Long =
        if (active) min(remainingMillis, Iap2VehicleStatus.UPDATE_INTERVAL_MILLIS) else remainingMillis

    private fun sendNow(send: (Iap2Frame) -> Unit) {
        val status = provider?.snapshot()
        if (status == null) {
            onProgress(VehicleDataDiagnostics.record("iap2-status-tx", "snapshot=missing sent=false"))
            return
        }
        send(Iap2VehicleStatus.update(status))
        lastSentNanos = nanoTime()
        val powertrainRanges = status.resolvedPowertrainRanges().entries
            .sortedBy { it.key.wireValue }
            .joinToString { "${it.key.name.lowercase()}=${it.value.rangeKm}km" }
        onProgress(
            VehicleDataDiagnostics.record(
                "iap2-status-tx",
                "messageId=0xa101 sent=true rangeKm=${status.rangeKm} $powertrainRanges " +
                    "batteryPercent=${String.format(java.util.Locale.US, "%.3f", status.batteryPercent)} " +
                    "charging=${status.charging} rangeWarning=${status.rangeWarning}",
            ),
        )
    }
}

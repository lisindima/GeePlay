package com.lllhys.geeplay.vehicle.geely.evcc

import android.os.SystemClock
import com.kooo.evcc.evccapi.EvccApiContract
import com.kooo.evcc.evccapi.EvccPropertyDescriptor
import com.kooo.evcc.evccapi.EvccPropertyRecord
import com.kooo.evcc.evccapi.EvccPropertySelector
import com.kooo.evcc.evccapi.EvccValue
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleEngineType
import com.shilapi.xcertplay.transport.VehiclePowertrainRange
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import com.shilapi.xcertplay.transport.VehicleSpeedSample
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import com.shilapi.xcertplay.vehicle.VehicleTyreReading
import com.shilapi.xcertplay.vehicle.VehicleTyreTelemetrySnapshot
import com.shilapi.xcertplay.vehicle.VehicleDataDiagnostics
import com.shilapi.xcertplay.vehicle.VehicleWheelPosition
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class EvccLogicalSignal {
    BATTERY_SOC,
    RANGE_TOTAL_KM,
    RANGE_GASOLINE_KM,
    RANGE_EV_KM,
    CHARGING,
    VEHICLE_SPEED,
    GEAR,
    TYRE_PRESSURE_FRONT_LEFT,
    TYRE_PRESSURE_FRONT_RIGHT,
    TYRE_PRESSURE_REAR_LEFT,
    TYRE_PRESSURE_REAR_RIGHT,
    TYRE_TEMPERATURE_FRONT_LEFT,
    TYRE_TEMPERATURE_FRONT_RIGHT,
    TYRE_TEMPERATURE_REAR_LEFT,
    TYRE_TEMPERATURE_REAR_RIGHT,
    CLUSTER_MAP_VISIBLE,
}

internal enum class EvccObservationOrigin {
    UNSPECIFIED,
    INITIAL_READ,
    PROPERTY_EVENT,
    REFRESH_READ,
}

internal data class EvccPropertyCandidate(
    val id: Long,
    val source: String,
    val areaId: Int = 0,
    val scale: Double = 1.0,
    val offset: Double = 0.0,
    val gearValues: Map<Long, VehicleGear> = emptyMap(),
)

internal data class EvccPropertyBinding(
    val signal: EvccLogicalSignal,
    val candidates: List<EvccPropertyCandidate>,
)

internal data class EvccResolvedProperty(
    val signal: EvccLogicalSignal,
    val candidate: EvccPropertyCandidate,
    val selector: EvccPropertySelector,
    val descriptor: EvccPropertyDescriptor,
) {
    fun matches(record: EvccPropertyRecord): Boolean {
        record.descriptor?.let { actual ->
            return actual.source == descriptor.source &&
                actual.canonicalId == descriptor.canonicalId &&
                actual.areaId == descriptor.areaId &&
                (descriptor.sourceGeneration == 0L || actual.sourceGeneration == 0L ||
                    actual.sourceGeneration == descriptor.sourceGeneration)
        }
        val requested = record.requestedSelector ?: return false
        return requested.source == selector.source && requested.id == selector.id && requested.areaId == selector.areaId
    }
}

internal object EvccGeelyPropertyProfile {
    private val geelyGearLeverValues = mapOf(
        0L to VehicleGear.PARK,
        1L to VehicleGear.NEUTRAL,
        2L to VehicleGear.REVERSE,
        3L to VehicleGear.DRIVE,
    )
    private val adaptGearValues = mapOf(
        0x200230L to VehicleGear.PARK,
        0x200240L to VehicleGear.NEUTRAL,
        0x200210L to VehicleGear.REVERSE,
        0x200220L to VehicleGear.DRIVE,
    )

    val bindings = listOf(
        EvccPropertyBinding(
            EvccLogicalSignal.BATTERY_SOC,
            listOf(
                EvccPropertyCandidate(
                    id = 1_051_136L,
                    source = EvccApiContract.Source.ADAPT_API,
                ), // SENSOR_TYPE_EV_BATTERY_LEVEL; G636/EVCC 3.2.14: 94 -> 88
            ),
        ),
        EvccPropertyBinding(
            EvccLogicalSignal.RANGE_TOTAL_KM,
            listOf(
                EvccPropertyCandidate(
                    id = 1_050_624L,
                    source = EvccApiContract.Source.ADAPT_API,
                ), // SENSOR_TYPE_ENDURANCE_MILEAGE; G636 range extender: 173 km total
            ),
        ),
        EvccPropertyBinding(
            EvccLogicalSignal.RANGE_GASOLINE_KM,
            listOf(
                EvccPropertyCandidate(
                    id = 1_054_720L,
                    source = EvccApiContract.Source.ADAPT_API,
                ), // SENSOR_TYPE_ENDURANCE_MILEAGE_FUEL; G636 range extender: 89 km
            ),
        ),
        EvccPropertyBinding(
            EvccLogicalSignal.RANGE_EV_KM,
            listOf(
                EvccPropertyCandidate(
                    id = 1_054_976L,
                    source = EvccApiContract.Source.ADAPT_API,
                ), // SENSOR_TYPE_ENDURANCE_MILEAGE_EV; G636: 84 km -> 78 km
            ),
        ),
        EvccPropertyBinding(
            EvccLogicalSignal.CHARGING,
            listOf(
                EvccPropertyCandidate(
                    id = 605_225_728L,
                    source = EvccApiContract.Source.ADAPT_API,
                ), // CHARGE_FUNC_CHARGING_STATE; 0 verified while not charging, 1 still requires charging test
            ),
        ),
        EvccPropertyBinding(
            EvccLogicalSignal.VEHICLE_SPEED,
            listOf(
                EvccPropertyCandidate(
                    id = 1_048_832L,
                    source = EvccApiContract.Source.ADAPT_API,
                ), // SENSOR_TYPE_CAR_SPEED; G636 moving trace confirmed meters per second
                EvccPropertyCandidate(
                    id = 1_055_232L,
                    source = EvccApiContract.Source.ADAPT_API,
                ), // SENSOR_TYPE_CAR_SPEED_FROM_IPK; same EVCC speed unit contract
            ),
        ),
        EvccPropertyBinding(
            EvccLogicalSignal.GEAR,
            listOf(
                EvccPropertyCandidate(
                    id = 0x21401A01L,
                    source = EvccApiContract.Source.CAPI,
                    gearValues = geelyGearLeverValues,
                ), // CLUSTER_GEAR_GEARLVRINDCN; G636 trace: 0=P, 1=N, 2=R, 3=D
                EvccPropertyCandidate(
                    id = 0x200200L,
                    source = EvccApiContract.Source.ADAPT_API,
                    gearValues = adaptGearValues,
                ), // SENSOR_TYPE_GEAR; G636 trace mirrors the CAPI lever state
            ),
        ),
        EvccPropertyBinding(
            EvccLogicalSignal.TYRE_PRESSURE_FRONT_LEFT,
            listOf(EvccPropertyCandidate(5_243_136L, EvccApiContract.Source.ADAPT_API)),
        ),
        EvccPropertyBinding(
            EvccLogicalSignal.TYRE_PRESSURE_FRONT_RIGHT,
            listOf(EvccPropertyCandidate(5_243_392L, EvccApiContract.Source.ADAPT_API)),
        ),
        EvccPropertyBinding(
            EvccLogicalSignal.TYRE_PRESSURE_REAR_LEFT,
            listOf(EvccPropertyCandidate(5_243_648L, EvccApiContract.Source.ADAPT_API)),
        ),
        EvccPropertyBinding(
            EvccLogicalSignal.TYRE_PRESSURE_REAR_RIGHT,
            listOf(EvccPropertyCandidate(5_243_904L, EvccApiContract.Source.ADAPT_API)),
        ),
        // G636/EVCC 3.3.0 exposes 77-80 while parked at ambient temperature: the source is °F.
        EvccPropertyBinding(
            EvccLogicalSignal.TYRE_TEMPERATURE_FRONT_LEFT,
            listOf(fahrenheitCandidate(5_244_160L)),
        ),
        EvccPropertyBinding(
            EvccLogicalSignal.TYRE_TEMPERATURE_FRONT_RIGHT,
            listOf(fahrenheitCandidate(5_244_416L)),
        ),
        EvccPropertyBinding(
            EvccLogicalSignal.TYRE_TEMPERATURE_REAR_LEFT,
            listOf(fahrenheitCandidate(5_244_672L)),
        ),
        EvccPropertyBinding(
            EvccLogicalSignal.TYRE_TEMPERATURE_REAR_RIGHT,
            listOf(fahrenheitCandidate(5_244_928L)),
        ),
    )

    private fun fahrenheitCandidate(id: Long) = EvccPropertyCandidate(
        id = id,
        source = EvccApiContract.Source.ADAPT_API,
        scale = 5.0 / 9.0,
        offset = -32.0 * 5.0 / 9.0,
    )
}

/** Conservative runtime-directory matching for properties whose IDs vary by Geely firmware. */
internal object EvccDynamicPropertyDiscovery {
    fun resolve(descriptors: Iterable<EvccPropertyDescriptor>): List<EvccResolvedProperty> =
        descriptors.mapNotNull(::resolve).distinctBy { Triple(it.signal, it.descriptor.source, it.descriptor.areaId) }

    private fun resolve(descriptor: EvccPropertyDescriptor): EvccResolvedProperty? {
        if (!readable(descriptor.access)) return null
        val text = listOfNotNull(
            descriptor.name,
            descriptor.description,
            descriptor.group,
            descriptor.areaName,
            descriptor.userNote,
        ).plus(descriptor.aliases).joinToString(" ").uppercase(Locale.ROOT)
        val tyreKind = when {
            listOf("PRESSURE", "PRESS", "TPMS").any(text::contains) &&
                listOf("TYRE", "TIRE", "WHEEL", "TPMS").any(text::contains) -> TyreKind.PRESSURE
            listOf("TEMPERATURE", "TEMP").any(text::contains) &&
                listOf("TYRE", "TIRE", "WHEEL", "TPMS").any(text::contains) -> TyreKind.TEMPERATURE
            else -> null
        }
        val signal = if (tyreKind != null) {
            tyreSignal(wheel(text, descriptor.areaId) ?: return null, tyreKind)
        } else if (clusterMapVisibility(text)) {
            EvccLogicalSignal.CLUSTER_MAP_VISIBLE
        } else {
            return null
        }
        val (scale, offset) = conversion(text, tyreKind)
        val candidate = EvccPropertyCandidate(
            id = descriptor.canonicalId,
            source = descriptor.source,
            areaId = descriptor.areaId,
            scale = scale,
            offset = offset,
        )
        return EvccResolvedProperty(
            signal,
            candidate,
            EvccPropertySelector(
                id = descriptor.canonicalId,
                source = descriptor.source,
                areaId = descriptor.areaId,
                resolutionMode = EvccApiContract.ResolutionMode.STRICT_UNIQUE,
            ),
            descriptor,
        )
    }

    private fun readable(access: String): Boolean = access.uppercase(Locale.ROOT).let {
        it == "R" || it == "RW" || "READ" in it
    }

    private fun wheel(text: String, areaId: Int): VehicleWheelPosition? = when {
        listOf("FRONT_LEFT", "LEFT_FRONT", "FRONT LEFT", "LEFT FRONT", "FL_TIRE", "FL_TYRE").any(text::contains) ->
            VehicleWheelPosition.FRONT_LEFT
        listOf("FRONT_RIGHT", "RIGHT_FRONT", "FRONT RIGHT", "RIGHT FRONT", "FR_TIRE", "FR_TYRE").any(text::contains) ->
            VehicleWheelPosition.FRONT_RIGHT
        listOf("REAR_LEFT", "LEFT_REAR", "REAR LEFT", "LEFT REAR", "RL_TIRE", "RL_TYRE").any(text::contains) ->
            VehicleWheelPosition.REAR_LEFT
        listOf("REAR_RIGHT", "RIGHT_REAR", "REAR RIGHT", "RIGHT REAR", "RR_TIRE", "RR_TYRE").any(text::contains) ->
            VehicleWheelPosition.REAR_RIGHT
        areaId == 1 -> VehicleWheelPosition.FRONT_LEFT
        areaId == 2 -> VehicleWheelPosition.FRONT_RIGHT
        areaId == 4 -> VehicleWheelPosition.REAR_LEFT
        areaId == 8 -> VehicleWheelPosition.REAR_RIGHT
        else -> null
    }

    private fun tyreSignal(position: VehicleWheelPosition, kind: TyreKind): EvccLogicalSignal = when (position to kind) {
        VehicleWheelPosition.FRONT_LEFT to TyreKind.PRESSURE -> EvccLogicalSignal.TYRE_PRESSURE_FRONT_LEFT
        VehicleWheelPosition.FRONT_RIGHT to TyreKind.PRESSURE -> EvccLogicalSignal.TYRE_PRESSURE_FRONT_RIGHT
        VehicleWheelPosition.REAR_LEFT to TyreKind.PRESSURE -> EvccLogicalSignal.TYRE_PRESSURE_REAR_LEFT
        VehicleWheelPosition.REAR_RIGHT to TyreKind.PRESSURE -> EvccLogicalSignal.TYRE_PRESSURE_REAR_RIGHT
        VehicleWheelPosition.FRONT_LEFT to TyreKind.TEMPERATURE -> EvccLogicalSignal.TYRE_TEMPERATURE_FRONT_LEFT
        VehicleWheelPosition.FRONT_RIGHT to TyreKind.TEMPERATURE -> EvccLogicalSignal.TYRE_TEMPERATURE_FRONT_RIGHT
        VehicleWheelPosition.REAR_LEFT to TyreKind.TEMPERATURE -> EvccLogicalSignal.TYRE_TEMPERATURE_REAR_LEFT
        VehicleWheelPosition.REAR_RIGHT to TyreKind.TEMPERATURE -> EvccLogicalSignal.TYRE_TEMPERATURE_REAR_RIGHT
        else -> error("Unsupported tyre signal $position/$kind")
    }

    private fun conversion(text: String, kind: TyreKind?): Pair<Double, Double> = when (kind) {
        TyreKind.PRESSURE -> when {
            "PSI" in text -> 6.894_757 to 0.0
            "BAR" in text -> 100.0 to 0.0
            else -> 1.0 to 0.0
        }
        TyreKind.TEMPERATURE -> if ("FAHRENHEIT" in text || "°F" in text) {
            (5.0 / 9.0) to (-32.0 * 5.0 / 9.0)
        } else {
            1.0 to 0.0
        }
        null -> 1.0 to 0.0
    }

    private fun clusterMapVisibility(text: String): Boolean =
        listOf("CLUSTER", "INSTRUMENT", "IPK", "DIM").any(text::contains) &&
            listOf("MAP_VISIBLE", "MAP SHOWN", "NAVIGATION_LAYOUT", "NAVI_MODE").any(text::contains)

    private enum class TyreKind { PRESSURE, TEMPERATURE }
}

internal data class EvccMotionSnapshot(
    val speedMetersPerSecond: Double,
    val gear: VehicleGear,
)

/** Thread-safe conversion and cache; it has no Binder or lifecycle knowledge. */
internal class EvccVehicleTelemetryStore(
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Observation(
        val value: EvccValue,
        val descriptor: EvccPropertyDescriptor,
        val candidate: EvccPropertyCandidate,
        val receivedAt: Long,
        val origin: EvccObservationOrigin,
    )

    private val values = LinkedHashMap<EvccLogicalSignal, Observation>()

    @Synchronized
    fun accept(
        binding: EvccResolvedProperty,
        record: EvccPropertyRecord,
        origin: EvccObservationOrigin = EvccObservationOrigin.UNSPECIFIED,
    ): Boolean {
        val value = record.value
        if (record.status !in RECORD_SUCCESS_STATUSES ||
            value == null || !value.initialized || value.status != EvccApiContract.Status.AVAILABLE
        ) {
            return values.remove(binding.signal) != null
        }
        values[binding.signal] = Observation(
            value,
            record.descriptor ?: binding.descriptor,
            binding.candidate,
            elapsedRealtime(),
            origin,
        )
        return true
    }

    @Synchronized
    fun clear() = values.clear()

    @Synchronized
    fun sourceState(source: String, sourceGeneration: Long, status: String): Boolean {
        return values.entries.removeAll { (_, observation) ->
            observation.descriptor.source == source &&
                (status != EvccApiContract.Status.AVAILABLE ||
                    (observation.descriptor.sourceGeneration != 0L && sourceGeneration != 0L &&
                        observation.descriptor.sourceGeneration != sourceGeneration))
        }
    }

    @Synchronized
    fun retainSignals(signals: Set<EvccLogicalSignal>) {
        values.keys.retainAll(signals)
    }

    @Synchronized
    fun vehicleStatus(lowChargePercent: Int): VehicleStatusSnapshot? {
        val socObservation = values[EvccLogicalSignal.BATTERY_SOC] ?: return null
        val electricRangeObservation = values[EvccLogicalSignal.RANGE_EV_KM] ?: return null
        val chargingObservation = values[EvccLogicalSignal.CHARGING] ?: return null
        val totalRangeObservation = values[EvccLogicalSignal.RANGE_TOTAL_KM]
        val gasolineRangeObservation = values[EvccLogicalSignal.RANGE_GASOLINE_KM]
        if (!compatibleSourceGenerations(
                listOfNotNull(
                    socObservation,
                    electricRangeObservation,
                    chargingObservation,
                    totalRangeObservation,
                    gasolineRangeObservation,
                ),
            )
        ) {
            return null
        }
        val soc = number(socObservation)?.takeIf { it.isFinite() && it in 0.0..100.0 }
            ?: return null
        val electricRange = validRange(electricRangeObservation)
            ?: return null
        val gasolineRange = gasolineRangeObservation?.let(::validRange)
        val totalRange = totalRangeObservation?.let(::validRange)
        val charging = state(chargingObservation) ?: return null
        val electricRangeKm = electricRange.roundToInt()
        val gasolineRangeKm = gasolineRange?.roundToInt()
        val rangeKm = totalRange?.roundToInt()
            ?: gasolineRange?.let { (it + electricRange).roundToInt().coerceAtMost(65_535) }
            ?: electricRangeKm
        val fraction = soc / 100.0
        val maxElectricRangeKm = if (fraction > 0.05) {
            (electricRange / fraction).roundToInt().coerceIn(electricRangeKm, 65_535)
        } else {
            electricRangeKm
        }
        val electricRangeWarning = soc <= lowChargePercent.coerceIn(1, 50)
        val powertrainRanges = buildMap {
            gasolineRangeKm?.let { put(VehicleEngineType.GASOLINE, VehiclePowertrainRange(it)) }
            put(
                VehicleEngineType.ELECTRIC,
                VehiclePowertrainRange(
                    rangeKm = electricRangeKm,
                    rangeWarning = electricRangeWarning,
                    maxRangeKm = maxElectricRangeKm,
                ),
            )
        }
        return VehicleStatusSnapshot(
            rangeKm = rangeKm,
            // A low traction-battery SOC does not mean a range extender has low combined range.
            rangeWarning = gasolineRangeKm == null && electricRangeWarning,
            batteryPercent = soc,
            currentChargeWh = null,
            maxChargeWh = null,
            maxRangeKm = maxElectricRangeKm,
            charging = charging,
            powertrainRanges = powertrainRanges,
        )
    }

    @Synchronized
    fun motion(maxAgeMillis: Long = MOTION_MAX_AGE_MILLIS): EvccMotionSnapshot? {
        val speedObservation = values[EvccLogicalSignal.VEHICLE_SPEED] ?: return null
        val gearObservation = values[EvccLogicalSignal.GEAR] ?: return null
        if (!compatibleSourceGenerations(listOf(speedObservation, gearObservation))) return null
        val now = elapsedRealtime()
        if (now - speedObservation.receivedAt > maxAgeMillis || now - gearObservation.receivedAt > maxAgeMillis) return null
        val speed = number(speedObservation)?.times(speedObservation.candidate.scale)
            ?.takeIf { it.isFinite() && it in 0.0..MAX_SPEED_METERS_PER_SECOND }
            ?: return null
        val gear = gear(gearObservation) ?: return null
        return EvccMotionSnapshot(speed, gear)
    }

    @Synchronized
    fun parked(maxAgeMillis: Long): Boolean? {
        val observation = values[EvccLogicalSignal.GEAR] ?: return null
        if (elapsedRealtime() - observation.receivedAt > maxAgeMillis) return null
        return when (gear(observation)) {
            VehicleGear.PARK -> true
            VehicleGear.REVERSE, VehicleGear.NEUTRAL, VehicleGear.DRIVE -> false
            null -> null
        }
    }

    @Synchronized
    fun clusterMapVisible(maxAgeMillis: Long): Boolean? {
        val observation = values[EvccLogicalSignal.CLUSTER_MAP_VISIBLE] ?: return null
        if (elapsedRealtime() - observation.receivedAt > maxAgeMillis) return null
        return state(observation)
    }

    @Synchronized
    fun tyreTelemetry(maxAgeMillis: Long): VehicleTyreTelemetrySnapshot? {
        val now = elapsedRealtime()
        val wheels = buildMap<VehicleWheelPosition, VehicleTyreReading> {
            TYRE_SIGNAL_PAIRS.forEach { (position, signals) ->
                val pressure = this@EvccVehicleTelemetryStore.values[signals.first]
                    ?.takeIf { now - it.receivedAt <= maxAgeMillis }
                val temperature = this@EvccVehicleTelemetryStore.values[signals.second]
                    ?.takeIf { now - it.receivedAt <= maxAgeMillis }
                val pressureKpa = pressure?.let(::scaledNumber)?.takeIf { it.isFinite() && it in 0.0..1_500.0 }
                val temperatureC = temperature?.let(::scaledNumber)?.takeIf { it.isFinite() && it in -80.0..200.0 }
                if (pressureKpa != null || temperatureC != null) {
                    put(
                        position,
                        VehicleTyreReading(
                            pressureKpa = pressureKpa,
                            temperatureC = temperatureC,
                            observedAtElapsedMillis = maxOf(
                                pressure?.receivedAt ?: 0L,
                                temperature?.receivedAt ?: 0L,
                            ),
                        ),
                    )
                }
            }
        }
        return wheels.takeIf { it.isNotEmpty() }?.let(::VehicleTyreTelemetrySnapshot)
    }

    @Synchronized
    fun readySignals(): Set<EvccLogicalSignal> = values.keys.toSet()

    @Synchronized
    fun propertyDiagnostic(signal: EvccLogicalSignal): String {
        val observation = values[signal]
            ?: return "property signal=$signal state=missing"
        val raw = diagnosticValue(rawText(observation))
        val normalized = when (signal) {
            EvccLogicalSignal.GEAR -> gear(observation)?.letter?.toString()
            EvccLogicalSignal.CHARGING,
            EvccLogicalSignal.CLUSTER_MAP_VISIBLE,
            -> state(observation)?.toString()
            else -> scaledNumber(observation)?.let(::formatDecimal)
        } ?: "-"
        val sourceObservedAt = observation.value.observedAt
        val sourceAge = sourceAge(sourceObservedAt)
        return "property signal=$signal source=${observation.descriptor.source} " +
            "id=${observation.descriptor.canonicalId} area=${observation.descriptor.areaId} " +
            "generation=${observation.descriptor.sourceGeneration} type=${observation.descriptor.dataType} " +
            "raw=$raw normalized=$normalized unit=${diagnosticUnit(signal)} " +
            "sourceObservedAt=$sourceObservedAt sourceClock=${sourceAge?.domain ?: "unknown"} " +
            "sourceAgeMs=${sourceAge?.ageMillis ?: "unknown"} " +
            "origin=${observation.origin} ageMs=${(elapsedRealtime() - observation.receivedAt).coerceAtLeast(0)}"
    }

    @Synchronized
    fun propertyDiagnostics(): List<String> = EvccLogicalSignal.entries.map(::propertyDiagnostic)

    @Synchronized
    fun motionDiagnostics(): String {
        val now = elapsedRealtime()
        val speed = values[EvccLogicalSignal.VEHICLE_SPEED]
        val gear = values[EvccLogicalSignal.GEAR]
        val speedText = if (speed == null) {
            "speed=missing"
        } else {
            val raw = number(speed)
            val metersPerSecond = scaledNumber(speed)
            "speed={raw=${raw?.let(::formatDecimal) ?: "-"} " +
                "mps=${metersPerSecond?.let(::formatDecimal) ?: "-"} " +
                "origin=${speed.origin} ageMs=${(now - speed.receivedAt).coerceAtLeast(0)}}"
        }
        val gearText = if (gear == null) {
            "gear=missing"
        } else {
            "gear={raw=${rawText(gear)} gear=${gear(gear)?.letter ?: '-'} " +
                "origin=${gear.origin} ageMs=${(now - gear.receivedAt).coerceAtLeast(0)}}"
        }
        return "cache $speedText $gearText"
    }

    private fun number(observation: Observation): Double? = observation.value.let { value ->
        value.doubleValue
            ?: value.longValue?.toDouble()
            ?: value.stringValue?.trim()?.toDoubleOrNull()
            ?: value.rawValue?.trim()?.toDoubleOrNull()
            ?: value.displayValue.trim().substringBefore(' ').toDoubleOrNull()
    }

    private fun scaledNumber(observation: Observation): Double? =
        number(observation)?.let { it * observation.candidate.scale + observation.candidate.offset }

    private fun validRange(observation: Observation): Double? =
        number(observation)?.takeIf { it.isFinite() && it in 0.0..65_535.0 }

    private fun rawText(observation: Observation): String = observation.value.let { value ->
        value.longValue?.toString()
            ?: value.doubleValue?.let(::formatDecimal)
            ?: value.stringValue?.trim()
            ?: value.rawValue?.trim()
            ?: value.displayValue.trim().ifEmpty { "-" }
    }

    private fun formatDecimal(value: Double): String = String.format(Locale.US, "%.3f", value)

    private fun diagnosticValue(value: String): String = value
        .replace(Regex("[\\r\\n\\t ]+"), "_")
        .take(120)

    private fun sourceAge(observedAt: Long): SourceAge? {
        if (observedAt <= 0L) return null
        val elapsedNow = elapsedRealtime()
        val candidates = listOf(
            SourceAge("wall-ms", wallClockMillis() - observedAt),
            SourceAge("elapsed-ms", elapsedNow - observedAt),
            SourceAge("elapsed-us", elapsedNow - observedAt / 1_000L),
            SourceAge("elapsed-ns", elapsedNow - observedAt / 1_000_000L),
        )
        return candidates.minByOrNull { abs(it.ageMillis) }
            ?.takeIf { abs(it.ageMillis) <= MAX_SOURCE_AGE_MILLIS }
    }

    private fun diagnosticUnit(signal: EvccLogicalSignal): String = when (signal) {
        EvccLogicalSignal.BATTERY_SOC -> "percent"
        EvccLogicalSignal.RANGE_TOTAL_KM,
        EvccLogicalSignal.RANGE_GASOLINE_KM,
        EvccLogicalSignal.RANGE_EV_KM,
        -> "km"
        EvccLogicalSignal.VEHICLE_SPEED -> "m/s"
        EvccLogicalSignal.GEAR -> "gear"
        EvccLogicalSignal.CHARGING,
        EvccLogicalSignal.CLUSTER_MAP_VISIBLE,
        -> "boolean"
        EvccLogicalSignal.TYRE_PRESSURE_FRONT_LEFT,
        EvccLogicalSignal.TYRE_PRESSURE_FRONT_RIGHT,
        EvccLogicalSignal.TYRE_PRESSURE_REAR_LEFT,
        EvccLogicalSignal.TYRE_PRESSURE_REAR_RIGHT,
        -> "kPa"
        EvccLogicalSignal.TYRE_TEMPERATURE_FRONT_LEFT,
        EvccLogicalSignal.TYRE_TEMPERATURE_FRONT_RIGHT,
        EvccLogicalSignal.TYRE_TEMPERATURE_REAR_LEFT,
        EvccLogicalSignal.TYRE_TEMPERATURE_REAR_RIGHT,
        -> "C"
    }

    private fun state(observation: Observation): Boolean? {
        observation.value.booleanValue?.let { return it }
        val raw = observation.value.longValue
            ?: observation.value.stringValue?.trim()?.toLongOrNull()
            ?: observation.value.rawValue?.trim()?.toLongOrNull()
        listOfNotNull(
            enumLabel(observation.descriptor.enumOptions, raw),
            observation.value.displayValue.takeIf(String::isNotBlank),
            observation.value.stringValue,
            observation.value.rawValue,
        ).firstNotNullOfOrNull(::parseStateLabel)?.let { return it }
        return when (raw) {
            0L -> false
            1L -> true
            else -> null
        }
    }

    private fun gear(observation: Observation): VehicleGear? {
        val raw = observation.value.longValue
            ?: observation.value.stringValue?.trim()?.toLongOrNull()
            ?: observation.value.rawValue?.trim()?.toLongOrNull()
        raw?.let(observation.candidate.gearValues::get)?.let { return it }
        val labels = listOfNotNull(
            enumLabel(observation.descriptor.enumOptions, raw),
            observation.value.displayValue,
            observation.value.stringValue,
            observation.value.rawValue,
        )
        return labels.firstNotNullOfOrNull(::parseGearLabel)
    }

    private fun enumLabel(options: List<String>, raw: Long?): String? {
        if (raw == null) return null
        return options.firstNotNullOfOrNull { option ->
            ENUM_OPTION.matchEntire(option.trim())?.let { match ->
                val left = match.groupValues[1].toLongOrNull()
                val right = match.groupValues[4].toLongOrNull()
                when (raw) {
                    left -> match.groupValues[2]
                    right -> match.groupValues[3]
                    else -> null
                }
            }
        }
    }

    private fun parseGearLabel(value: String): VehicleGear? {
        val normalized = value.trim().uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z]+"), "_")
            .trim('_')
        val tokens = normalized.split('_').filter(String::isNotEmpty).toSet()
        return when {
            normalized in setOf("P", "PARK", "PARKING") || "PARK" in tokens || "PARKING" in tokens -> VehicleGear.PARK
            normalized in setOf("R", "REVERSE") || "REVERSE" in tokens -> VehicleGear.REVERSE
            normalized in setOf("N", "NEUTRAL") || "NEUTRAL" in tokens -> VehicleGear.NEUTRAL
            normalized in setOf("D", "DRIVE", "FORWARD") || "DRIVE" in tokens || "FORWARD" in tokens -> VehicleGear.DRIVE
            else -> null
        }
    }

    private fun parseStateLabel(value: String?): Boolean? {
        val normalized = value?.trim()?.uppercase(Locale.ROOT)
            ?.replace(Regex("[^A-Z]+"), "_")
            ?.trim('_')
            ?: return null
        val tokens = normalized.split('_').filter(String::isNotEmpty).toSet()
        if (tokens.any { it in OFF_TOKENS }) return false
        if (tokens.any { it in ON_TOKENS }) return true
        return null
    }

    private fun compatibleSourceGenerations(observations: List<Observation>): Boolean =
        observations.isNotEmpty() && observations.groupBy { it.descriptor.source }.values.all { sameSource ->
            sameSource.map { it.descriptor.sourceGeneration }.distinct().size == 1
        }

    private companion object {
        const val MOTION_MAX_AGE_MILLIS = 5_000L
        const val MAX_SPEED_METERS_PER_SECOND = 100.0
        const val MAX_SOURCE_AGE_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        val RECORD_SUCCESS_STATUSES = setOf(EvccApiContract.Status.OK, EvccApiContract.Status.AVAILABLE)
        val ENUM_OPTION = Regex("^\\s*(?:(-?\\d+)\\s*[:=]\\s*(.+)|(.+?)\\s*[:=]\\s*(-?\\d+))\\s*$")
        val ON_TOKENS = setOf("ON", "ACTIVE", "CHARGING", "CONNECTED", "TRUE")
        val OFF_TOKENS = setOf("OFF", "INACTIVE", "STOPPED", "DISCONNECTED", "FALSE", "NOT")
        val TYRE_SIGNAL_PAIRS = mapOf(
            VehicleWheelPosition.FRONT_LEFT to
                (EvccLogicalSignal.TYRE_PRESSURE_FRONT_LEFT to EvccLogicalSignal.TYRE_TEMPERATURE_FRONT_LEFT),
            VehicleWheelPosition.FRONT_RIGHT to
                (EvccLogicalSignal.TYRE_PRESSURE_FRONT_RIGHT to EvccLogicalSignal.TYRE_TEMPERATURE_FRONT_RIGHT),
            VehicleWheelPosition.REAR_LEFT to
                (EvccLogicalSignal.TYRE_PRESSURE_REAR_LEFT to EvccLogicalSignal.TYRE_TEMPERATURE_REAR_LEFT),
            VehicleWheelPosition.REAR_RIGHT to
                (EvccLogicalSignal.TYRE_PRESSURE_REAR_RIGHT to EvccLogicalSignal.TYRE_TEMPERATURE_REAR_RIGHT),
        )
    }

    private data class SourceAge(
        val domain: String,
        val ageMillis: Long,
    )
}

/** Samples the latest EVCC speed cache without performing Binder work on the iAP2 loop. */
internal class EvccVehicleSpeedSource(
    private val telemetry: EvccVehicleTelemetryStore,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val requestRefresh: () -> Unit = {},
    private val onDiagnostic: (String) -> Unit = {},
    private val sampleMillis: Long = DEFAULT_SAMPLE_MILLIS,
) : VehicleSpeedSource {
    private val refreshEverySamples =
        ((REFRESH_INTERVAL_MILLIS + sampleMillis.coerceAtLeast(1L) - 1L) / sampleMillis.coerceAtLeast(1L)).toInt()
    private val lock = Any()
    private val samples = ArrayList<VehicleSpeedSample>()
    private var executor: ScheduledExecutorService? = null
    private var gear: VehicleGear? = null
    private var sampleCount = 0
    private var generation = 0L
    private var refreshRequests = 0L
    private var validSamples = 0L
    private var missingSamples = 0L
    private var drains = 0L
    private var emptyDrains = 0L
    private var speedChanges = 0L
    private var lastSpeed: Double? = null
    private var lastGear: VehicleGear? = null
    private var lastMoving: Boolean? = null
    private var lastMotionAvailable: Boolean? = null
    private var lastDrainHadSamples: Boolean? = null
    private var lastSampleAt: Long? = null

    init {
        require(sampleMillis in 50L..1_000L) { "Unsupported EVCC wheel-speed sample interval" }
    }

    override fun start() {
        val started = synchronized(lock) {
            if (executor != null) return@synchronized false
            samples.clear()
            gear = null
            sampleCount = 0
            refreshRequests = 0
            validSamples = 0
            missingSamples = 0
            drains = 0
            emptyDrains = 0
            speedChanges = 0
            lastSpeed = null
            lastGear = null
            lastMoving = null
            lastMotionAvailable = null
            lastDrainHadSamples = null
            lastSampleAt = null
            val currentGeneration = ++generation
            executor = Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "geeplay-evcc-speed").apply { isDaemon = true }
            }.also {
                it.scheduleWithFixedDelay(
                    { sample(currentGeneration) },
                    0,
                    sampleMillis,
                    TimeUnit.MILLISECONDS,
                )
            }
            true
        }
        if (started) diagnose("speed-source start")
    }

    override fun stop() {
        val stopped = synchronized(lock) {
            val running = executor ?: return@synchronized null
            val summary = diagnosticsLocked(elapsedRealtime())
            executor = null
            generation += 1
            samples.clear()
            gear = null
            running to summary
        }
        stopped?.first?.shutdownNow()
        stopped?.let { diagnose("speed-source stop ${it.second}") }
    }

    override fun drain(): VehicleSpeedReading? {
        var diagnostic: String? = null
        val reading = synchronized(lock) {
            val currentGear = gear
            if (currentGear == null || samples.isEmpty()) {
                emptyDrains += 1
                if (lastDrainHadSamples != false) {
                    diagnostic = "speed-source drain=empty ${diagnosticsLocked(elapsedRealtime())}"
                }
                lastDrainHadSamples = false
                null
            } else {
                drains += 1
                val result = VehicleSpeedReading(currentGear, samples.toList())
                samples.clear()
                if (lastDrainHadSamples != true || drains == 1L || drains % DIAGNOSTIC_DRAIN_INTERVAL == 0L) {
                    diagnostic = "speed-source drain=reading count=$drains samples=${result.samples.size} " +
                        diagnosticsLocked(elapsedRealtime())
                }
                lastDrainHadSamples = true
                result
            }
        }
        diagnostic?.let(::diagnose)
        return reading
    }

    fun invalidate() = synchronized(lock) {
        samples.clear()
        gear = null
        lastMotionAvailable = false
        lastDrainHadSamples = null
    }

    fun diagnostics(): String = synchronized(lock) { diagnosticsLocked(elapsedRealtime()) }

    private fun sample(expectedGeneration: Long) {
        val refresh = synchronized(lock) {
            if (executor == null || generation != expectedGeneration) return
            val shouldRefresh = sampleCount++ % refreshEverySamples == 0
            if (shouldRefresh) refreshRequests += 1
            shouldRefresh
        }
        if (refresh) requestRefresh()
        val current = telemetry.motion()
        if (current == null) {
            val diagnostic = synchronized(lock) {
                if (executor == null || generation != expectedGeneration) return
                missingSamples += 1
                val shouldReport = lastMotionAvailable != false || missingSamples % refreshEverySamples == 0L
                lastMotionAvailable = false
                if (shouldReport) "speed-source sample=missing ${diagnosticsLocked(elapsedRealtime())}" else null
            }
            diagnostic?.let(::diagnose)
            return
        }
        val timestamp = elapsedRealtime()
        val diagnostic = synchronized(lock) {
            if (executor == null || generation != expectedGeneration) return
            val previousGear = gear
            val previousSpeed = lastSpeed
            val previousMoving = lastMoving
            if (previousGear != null && previousGear != current.gear) samples.clear()
            gear = current.gear
            if (samples.size == MAX_SAMPLES) samples.removeAt(0)
            samples += VehicleSpeedSample(timestamp, current.speedMetersPerSecond)
            validSamples += 1
            if (previousSpeed != null && abs(previousSpeed - current.speedMetersPerSecond) >= SPEED_CHANGE_EPSILON_MPS) {
                speedChanges += 1
            }
            val moving = current.speedMetersPerSecond >= MOVING_THRESHOLD_MPS
            val shouldReport = lastMotionAvailable != true || previousGear != current.gear ||
                previousMoving != null && previousMoving != moving ||
                validSamples % refreshEverySamples == 0L
            lastMotionAvailable = true
            lastSpeed = current.speedMetersPerSecond
            lastGear = current.gear
            lastMoving = moving
            lastSampleAt = timestamp
            if (shouldReport) "speed-source sample=ready ${diagnosticsLocked(timestamp)}" else null
        }
        diagnostic?.let(::diagnose)
    }

    private fun diagnosticsLocked(now: Long): String {
        val speedText = lastSpeed?.let { String.format(Locale.US, "%.3f", it) } ?: "-"
        val age = lastSampleAt?.let { (now - it).coerceAtLeast(0) }?.toString() ?: "-"
        return "started=${executor != null} sampleIntervalMs=$sampleMillis " +
            "refreshEverySamples=$refreshEverySamples refreshes=$refreshRequests validSamples=$validSamples " +
            "missingSamples=$missingSamples queued=${samples.size} drains=$drains emptyDrains=$emptyDrains " +
            "changes=$speedChanges lastSpeedMps=$speedText gear=${lastGear?.letter ?: '-'} sampleAgeMs=$age"
    }

    private fun diagnose(message: String) =
        onDiagnostic(VehicleDataDiagnostics.record("normalize-speed", message))

    private companion object {
        const val DEFAULT_SAMPLE_MILLIS = 250L
        const val REFRESH_INTERVAL_MILLIS = 1_000L
        const val MAX_SAMPLES = 40
        const val DIAGNOSTIC_DRAIN_INTERVAL = 10L
        const val SPEED_CHANGE_EPSILON_MPS = 0.05
        const val MOVING_THRESHOLD_MPS = 0.1
    }
}

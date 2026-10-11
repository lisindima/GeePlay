package com.lllhys.geeplay.vehicle.geely.evcc

import android.os.SystemClock
import com.kooo.evcc.evccapi.EvccApiContract
import com.kooo.evcc.evccapi.EvccPropertyDescriptor
import com.kooo.evcc.evccapi.EvccPropertyRecord
import com.kooo.evcc.evccapi.EvccPropertySelector
import com.kooo.evcc.evccapi.EvccValue
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import com.shilapi.xcertplay.transport.VehicleSpeedSample
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal enum class EvccLogicalSignal {
    BATTERY_SOC,
    RANGE_EV_KM,
    CHARGING,
    VEHICLE_SPEED,
    GEAR,
}

internal data class EvccPropertyCandidate(
    val id: Long,
    val source: String,
    val areaId: Int = 0,
    val scale: Double = 1.0,
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
    private val androidGearValues = buildMap {
        put(1L, VehicleGear.NEUTRAL)
        put(2L, VehicleGear.REVERSE)
        put(4L, VehicleGear.PARK)
        listOf(8L, 16L, 32L, 64L, 128L, 256L, 512L, 1_024L, 2_048L, 4_096L, 8_192L, 16_384L)
            .forEach { put(it, VehicleGear.DRIVE) }
    }

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
                    scale = 1.0 / 3.6,
                ), // SENSOR_TYPE_CAR_SPEED; unit conversion still requires a moving-vehicle test
                EvccPropertyCandidate(
                    id = 1_055_232L,
                    source = EvccApiContract.Source.ADAPT_API,
                    scale = 1.0 / 3.6,
                ), // SENSOR_TYPE_CAR_SPEED_FROM_IPK
            ),
        ),
        EvccPropertyBinding(
            EvccLogicalSignal.GEAR,
            listOf(
                EvccPropertyCandidate(
                    id = 289_408_000L,
                    source = EvccApiContract.Source.CAPI,
                    gearValues = androidGearValues,
                ), // Android VehiclePropertyIds.GEAR_SELECTION; G636 returned 4=PARK
            ),
        ),
    )
}

internal data class EvccMotionSnapshot(
    val speedMetersPerSecond: Double,
    val gear: VehicleGear,
)

/** Thread-safe conversion and cache; it has no Binder or lifecycle knowledge. */
internal class EvccVehicleTelemetryStore(
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    private data class Observation(
        val value: EvccValue,
        val descriptor: EvccPropertyDescriptor,
        val candidate: EvccPropertyCandidate,
        val receivedAt: Long,
    )

    private val values = LinkedHashMap<EvccLogicalSignal, Observation>()

    @Synchronized
    fun accept(binding: EvccResolvedProperty, record: EvccPropertyRecord): Boolean {
        val value = record.value
        if (record.status != EvccApiContract.Status.AVAILABLE ||
            value == null || !value.initialized || value.status != EvccApiContract.Status.AVAILABLE
        ) {
            return values.remove(binding.signal) != null
        }
        values[binding.signal] = Observation(
            value,
            record.descriptor ?: binding.descriptor,
            binding.candidate,
            elapsedRealtime(),
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
        val rangeObservation = values[EvccLogicalSignal.RANGE_EV_KM] ?: return null
        val chargingObservation = values[EvccLogicalSignal.CHARGING] ?: return null
        if (!compatibleSourceGenerations(listOf(socObservation, rangeObservation, chargingObservation))) {
            return null
        }
        val soc = number(socObservation)?.takeIf { it.isFinite() && it in 0.0..100.0 }
            ?: return null
        val range = number(rangeObservation)?.takeIf { it.isFinite() && it in 0.0..65_535.0 }
            ?: return null
        val charging = state(chargingObservation) ?: return null
        val rangeKm = range.roundToInt()
        val fraction = soc / 100.0
        val maxRangeKm = if (fraction > 0.05) {
            (range / fraction).roundToInt().coerceIn(rangeKm, 65_535)
        } else {
            rangeKm
        }
        return VehicleStatusSnapshot(
            rangeKm = rangeKm,
            rangeWarning = soc <= lowChargePercent.coerceIn(1, 50),
            batteryPercent = soc,
            currentChargeWh = null,
            maxChargeWh = null,
            maxRangeKm = maxRangeKm,
            charging = charging,
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
    fun readySignals(): Set<EvccLogicalSignal> = values.keys.toSet()

    private fun number(observation: Observation): Double? = observation.value.let { value ->
        value.doubleValue
            ?: value.longValue?.toDouble()
            ?: value.stringValue?.trim()?.toDoubleOrNull()
            ?: value.rawValue?.trim()?.toDoubleOrNull()
            ?: value.displayValue.trim().substringBefore(' ').toDoubleOrNull()
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
        val ENUM_OPTION = Regex("^\\s*(?:(-?\\d+)\\s*[:=]\\s*(.+)|(.+?)\\s*[:=]\\s*(-?\\d+))\\s*$")
        val ON_TOKENS = setOf("ON", "ACTIVE", "CHARGING", "CONNECTED", "TRUE")
        val OFF_TOKENS = setOf("OFF", "INACTIVE", "STOPPED", "DISCONNECTED", "FALSE", "NOT")
    }
}

/** Samples the latest EVCC speed cache without performing Binder work on the iAP2 loop. */
internal class EvccVehicleSpeedSource(
    private val telemetry: EvccVehicleTelemetryStore,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val requestRefresh: () -> Unit = {},
) : VehicleSpeedSource {
    private val lock = Any()
    private val samples = ArrayList<VehicleSpeedSample>()
    private var executor: ScheduledExecutorService? = null
    private var gear: VehicleGear? = null
    private var sampleCount = 0
    private var generation = 0L

    override fun start() = synchronized(lock) {
        if (executor != null) return@synchronized
        samples.clear()
        gear = null
        sampleCount = 0
        val currentGeneration = ++generation
        executor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "geeplay-evcc-speed").apply { isDaemon = true }
        }.also {
            it.scheduleWithFixedDelay(
                { sample(currentGeneration) },
                0,
                SAMPLE_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    override fun stop() {
        val running = synchronized(lock) {
            executor.also {
                executor = null
                generation += 1
                samples.clear()
                gear = null
            }
        }
        running?.shutdownNow()
    }

    override fun drain(): VehicleSpeedReading? = synchronized(lock) {
        val currentGear = gear ?: return@synchronized null
        if (samples.isEmpty()) return@synchronized null
        VehicleSpeedReading(currentGear, samples.toList()).also { samples.clear() }
    }

    fun invalidate() = synchronized(lock) {
        samples.clear()
        gear = null
    }

    private fun sample(expectedGeneration: Long) {
        val refresh = synchronized(lock) {
            if (executor == null || generation != expectedGeneration) return
            sampleCount++ % REFRESH_EVERY_SAMPLES == 0
        }
        if (refresh) requestRefresh()
        val current = telemetry.motion() ?: return
        synchronized(lock) {
            if (executor == null || generation != expectedGeneration) return
            if (gear != null && gear != current.gear) samples.clear()
            gear = current.gear
            if (samples.size == MAX_SAMPLES) samples.removeAt(0)
            samples += VehicleSpeedSample(elapsedRealtime(), current.speedMetersPerSecond)
        }
    }

    private companion object {
        const val SAMPLE_MILLIS = 250L
        const val MAX_SAMPLES = 40
        const val REFRESH_EVERY_SAMPLES = 4
    }
}

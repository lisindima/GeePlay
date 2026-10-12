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
    fun readySignals(): Set<EvccLogicalSignal> = values.keys.toSet()

    @Synchronized
    fun motionDiagnostics(): String {
        val now = elapsedRealtime()
        val speed = values[EvccLogicalSignal.VEHICLE_SPEED]
        val gear = values[EvccLogicalSignal.GEAR]
        val speedText = if (speed == null) {
            "speed=missing"
        } else {
            val raw = number(speed)
            val metersPerSecond = raw?.times(speed.candidate.scale)
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
        val RECORD_SUCCESS_STATUSES = setOf(EvccApiContract.Status.OK, EvccApiContract.Status.AVAILABLE)
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
    private val onDiagnostic: (String) -> Unit = {},
) : VehicleSpeedSource {
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
                    SAMPLE_MILLIS,
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
            val shouldRefresh = sampleCount++ % REFRESH_EVERY_SAMPLES == 0
            if (shouldRefresh) refreshRequests += 1
            shouldRefresh
        }
        if (refresh) requestRefresh()
        val current = telemetry.motion()
        if (current == null) {
            val diagnostic = synchronized(lock) {
                if (executor == null || generation != expectedGeneration) return
                missingSamples += 1
                val shouldReport = lastMotionAvailable != false || missingSamples % DIAGNOSTIC_SAMPLE_INTERVAL == 0L
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
                validSamples % DIAGNOSTIC_SAMPLE_INTERVAL == 0L
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
        return "started=${executor != null} refreshes=$refreshRequests validSamples=$validSamples " +
            "missingSamples=$missingSamples queued=${samples.size} drains=$drains emptyDrains=$emptyDrains " +
            "changes=$speedChanges lastSpeedMps=$speedText gear=${lastGear?.letter ?: '-'} sampleAgeMs=$age"
    }

    private fun diagnose(message: String) = onDiagnostic("$DEBUG_PREFIX $message")

    private companion object {
        const val SAMPLE_MILLIS = 250L
        const val MAX_SAMPLES = 40
        const val REFRESH_EVERY_SAMPLES = 4
        const val DIAGNOSTIC_SAMPLE_INTERVAL = 20L
        const val DIAGNOSTIC_DRAIN_INTERVAL = 10L
        const val SPEED_CHANGE_EPSILON_MPS = 0.05
        const val MOVING_THRESHOLD_MPS = 0.1
        const val DEBUG_PREFIX = "[DEBUG-a4f2]"
    }
}

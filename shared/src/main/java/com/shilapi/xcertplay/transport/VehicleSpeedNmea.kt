package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.vehicle.VehicleDataDiagnostics
import java.util.Locale

enum class VehicleGear(val letter: Char) { PARK('P'), REVERSE('R'), NEUTRAL('N'), DRIVE('D') }

/** One wheel-speed reading; [elapsedMillis] is on the monotonic clock since boot. */
data class VehicleSpeedSample(val elapsedMillis: Long, val metersPerSecond: Double)

/** Wheel-speed readings collected since the last sentence, with the gear at the time. */
data class VehicleSpeedReading(val gear: VehicleGear, val samples: List<VehicleSpeedSample>)

/** Car wheel speed for dead reckoning; sampled in the background so the iAP2 loop never waits. */
interface VehicleSpeedSource {
    fun start()

    fun stop()

    /** Readings since the previous call, or null when there are none. */
    fun drain(): VehicleSpeedReading?
}

/**
 * Apple's `$PASCD` vehicle-speed sentence, sent in iAP2 0xFFFB next to GGA/RMC. The layout follows a
 * real head unit's log: `$PASCD,17877.092,C,P,0,2,0.00,0.000,0.16,0.000*54` is the first sample's
 * time in seconds, `C`, the gear, `0`, the sample count, then offset/speed (m/s) pairs. What `C` and
 * the `0` mean is not public, so they are copied as seen.
 */
object PascdEncoder {
    fun encode(reading: VehicleSpeedReading): String? {
        val samples = reading.samples.takeIf { it.isNotEmpty() } ?: return null
        val start = samples.first().elapsedMillis
        val body = buildString {
            append("PASCD,").append(format("%.3f", start / 1000.0)).append(",C,")
            append(reading.gear.letter).append(",0,").append(samples.size)
            for (sample in samples) {
                append(',').append(format("%.2f", (sample.elapsedMillis - start) / 1000.0))
                append(',').append(format("%.3f", sample.metersPerSecond.coerceAtLeast(0.0)))
            }
        }
        var checksum = 0
        for (character in body) checksum = checksum xor character.code
        return "$$body*${format("%02X", checksum)}\r\n"
    }

    private fun format(format: String, vararg arguments: Any): String = String.format(Locale.US, format, *arguments)
}

/**
 * Adds `$PASCD` to [position]'s GGA/RMC when the iPhone asked for vehicle speed. Speed is still sent
 * when there is no position fix, as in a tunnel, which is when the iPhone needs it most.
 */
class VehicleSpeedLocationProvider(
    private val position: Iap2LocationProvider,
    private val speed: VehicleSpeedSource,
    private val onDiagnostic: (String) -> Unit = {},
) : Iap2LocationProvider {
    @Volatile private var speedRequested = false
    private var drainAttempts = 0
    private var emptyDrains = 0
    private var packets = 0
    private var drainedSamples = 0
    private var lastDrainHadReading: Boolean? = null

    override fun onRequested(components: Set<Int>) {
        speedRequested = Iap2LocationMessages.VEHICLE_SPEED_DATA in components
        drainAttempts = 0
        emptyDrains = 0
        packets = 0
        drainedSamples = 0
        lastDrainHadReading = null
        diagnose("wheel-speed request requested=$speedRequested components=${components.toSortedSet()}")
        position.onRequested(components)
    }

    override fun start(): Boolean {
        val positionStarted = position.start()
        if (speedRequested) speed.start()
        val active = positionStarted || speedRequested
        diagnose(
            "wheel-speed provider start requested=$speedRequested " +
                "positionStarted=$positionStarted active=$active",
        )
        return active
    }

    override fun stop() {
        speed.stop()
        position.stop()
        diagnose(
            "wheel-speed provider stop packets=$packets samples=$drainedSamples " +
                "drainAttempts=$drainAttempts emptyDrains=$emptyDrains",
        )
    }

    override fun latestNmea(): String? {
        val fix = position.latestNmea()
        val reading = if (speedRequested) speed.drain() else null
        if (speedRequested) diagnoseDrain(reading)
        val pascd = reading?.let(PascdEncoder::encode)
        return if (fix == null) pascd else fix + pascd.orEmpty()
    }

    private fun diagnoseDrain(reading: VehicleSpeedReading?) {
        drainAttempts += 1
        if (reading == null) {
            emptyDrains += 1
            if (lastDrainHadReading != false) {
                diagnose(
                    "wheel-speed drain=empty attempts=$drainAttempts packets=$packets " +
                        "emptyDrains=$emptyDrains",
                )
            }
            lastDrainHadReading = false
            return
        }
        packets += 1
        drainedSamples += reading.samples.size
        if (lastDrainHadReading != true || packets == 1 || packets % DIAGNOSTIC_PACKET_INTERVAL == 0) {
            val speeds = reading.samples.map(VehicleSpeedSample::metersPerSecond)
            diagnose(
                "wheel-speed drain=reading batch=$packets packets=$packets samples=${reading.samples.size} " +
                    "totalSamples=$drainedSamples gear=${reading.gear.letter} " +
                    "firstMps=${speeds.firstOrNull()?.let { format("%.3f", it) } ?: "-"} " +
                    "lastMps=${speeds.lastOrNull()?.let { format("%.3f", it) } ?: "-"} " +
                    "minMps=${speeds.minOrNull()?.let { format("%.3f", it) } ?: "-"} " +
                    "maxMps=${speeds.maxOrNull()?.let { format("%.3f", it) } ?: "-"} " +
                    "emptyDrains=$emptyDrains",
            )
        }
        lastDrainHadReading = true
    }

    private fun diagnose(message: String) =
        onDiagnostic(VehicleDataDiagnostics.record("pascd", message))

    private fun format(format: String, vararg arguments: Any): String =
        String.format(Locale.US, format, *arguments)

    private companion object {
        const val DIAGNOSTIC_PACKET_INTERVAL = 1
    }
}

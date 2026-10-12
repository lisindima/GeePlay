package com.lllhys.geeplay.vehicle.geely.evcc

import android.content.Context
import com.kooo.evcc.evccapi.EvccApiContract
import com.kooo.evcc.evccapi.EvccPropertyRecord
import com.kooo.evcc.evccapi.EvccPropertySelector
import com.kooo.evcc.evccapi.EvccValue
import com.shilapi.xcertplay.media.AmbientLampSnapshot
import com.shilapi.xcertplay.media.AmbientLightingClient
import com.shilapi.xcertplay.media.AmbientLightingProvider
import java.util.concurrent.CompletableFuture

internal data class EvccAmbientLightingProfile(
    val id: String,
    val frontColor: EvccPropertySelector,
    val rearColor: EvccPropertySelector,
    val frontBrightness: EvccPropertySelector,
    val rearBrightness: EvccPropertySelector,
)

/**
 * EVCC typed-write ambient lighting. Profiles stay empty until exact G636 writable descriptors and
 * value ranges have completed validate/write/read-back/restore on the real vehicle.
 */
class EvccAmbientLightingProvider private constructor(
    private val gateway: EvccVehicleControlGateway,
    private val profile: EvccAmbientLightingProfile?,
) : AmbientLightingProvider {
    constructor(gateway: EvccVehicleControlGateway) : this(gateway, VALIDATED_PROFILES.firstOrNull())
    override fun create(context: Context): AmbientLightingClient =
        EvccAmbientLightingClient(
            gateway,
            profile ?: throw UnsupportedOperationException("No validated Geely ambient-light profile"),
        )

    override fun checkSupport(context: Context): CompletableFuture<Boolean> =
        CompletableFuture.completedFuture(profile != null)

    fun diagnostics(): String = "EVCC ambient lighting: profile=${profile?.id ?: "none"} " +
        "state=${if (profile == null) "EVIDENCE_PENDING" else "AVAILABLE"}"

    companion object {
        /** Populated only after a G636/firmware-specific typed-write round trip is recorded. */
        internal val VALIDATED_PROFILES: List<EvccAmbientLightingProfile> = emptyList()
    }
}

private class EvccAmbientLightingClient(
    private val gateway: EvccVehicleControlGateway,
    private val profile: EvccAmbientLightingProfile,
) : AmbientLightingClient {
    @Volatile private var failed = false
    @Volatile private var closed = false
    private var original: AmbientLampSnapshot? = null

    override fun unhealthy(): Boolean = failed || closed

    override fun begin(seed: AmbientLampSnapshot?): CompletableFuture<AmbientLampSnapshot> {
        if (closed) return failedFuture(IllegalStateException("ambient client closed"))
        seed?.takeIf(AmbientLampSnapshot::valid)?.let {
            original = it
            return CompletableFuture.completedFuture(it)
        }
        val frontColor = readLong(profile.frontColor)
        val rearColor = readLong(profile.rearColor)
        val frontBrightness = readLong(profile.frontBrightness)
        val rearBrightness = readLong(profile.rearBrightness)
        return CompletableFuture.allOf(frontColor, rearColor, frontBrightness, rearBrightness).thenApply {
            AmbientLampSnapshot(
                frontColor.get().toInt(),
                rearColor.get().toInt(),
                frontBrightness.get().toInt(),
                rearBrightness.get().toInt(),
                3,
            ).also { snapshot ->
                check(snapshot.valid()) { "EVCC returned an unsupported ambient-light state" }
                original = snapshot
            }
        }.whenComplete { _, error -> if (error != null) failed = true }
    }

    override fun apply(area: Int, color: Int, brightness: Int): CompletableFuture<Unit> {
        if (closed) return failedFuture(IllegalStateException("ambient client closed"))
        require(area in 1..3 && color in 1..31 && brightness in 0..6)
        val writes = buildList {
            if (area == 1 || area == 3) {
                add(profile.frontColor to color.toLong())
                add(profile.frontBrightness to brightness.toLong())
            }
            if (area == 2 || area == 3) {
                add(profile.rearColor to color.toLong())
                add(profile.rearBrightness to brightness.toLong())
            }
        }
        return writeAll(writes).whenComplete { _, error -> if (error != null) failed = true }
    }

    override fun stop(): CompletableFuture<Unit> {
        val snapshot = original ?: return CompletableFuture.completedFuture(Unit)
        val writes = listOf(
            profile.frontColor to snapshot.frontColor.toLong(),
            profile.rearColor to snapshot.backColor.toLong(),
            profile.frontBrightness to snapshot.frontBrightness.toLong(),
            profile.rearBrightness to snapshot.backBrightness.toLong(),
        )
        return writeAll(writes).whenComplete { _, error -> if (error == null) original = null else failed = true }
    }

    override fun close() { closed = true }

    private fun writeAll(writes: List<Pair<EvccPropertySelector, Long>>): CompletableFuture<Unit> {
        var chain = CompletableFuture.completedFuture(Unit)
        writes.forEach { (selector, value) ->
            chain = chain.thenCompose { writeAndReadBack(selector, value) }
        }
        return chain
    }

    private fun writeAndReadBack(selector: EvccPropertySelector, value: Long): CompletableFuture<Unit> =
        gateway.write(selector, EvccValue.ofLong(value), validateOnly = true).thenCompose { validation ->
            check(validation.isSuccess) { "EVCC ambient validation failed: ${validation.status}" }
            gateway.write(selector, EvccValue.ofLong(value), validateOnly = false)
        }.thenCompose { write ->
            check(write.isSuccess) { "EVCC ambient write failed: ${write.status}" }
            readLong(selector)
        }.thenApply { actual ->
            check(actual == value) { "EVCC ambient read-back mismatch" }
            Unit
        }

    private fun readLong(selector: EvccPropertySelector): CompletableFuture<Long> =
        gateway.read(selector, forceRefresh = true).thenApply(::recordLong)

    private fun recordLong(record: EvccPropertyRecord): Long {
        check(record.status in setOf(EvccApiContract.Status.OK, EvccApiContract.Status.AVAILABLE)) {
            "EVCC ambient read failed: ${record.status}"
        }
        return record.value?.longValue
            ?: record.value?.doubleValue?.toLong()
            ?: record.value?.rawValue?.toLongOrNull()
            ?: error("EVCC ambient value is not numeric")
    }

    private fun <T> failedFuture(error: Throwable): CompletableFuture<T> =
        CompletableFuture<T>().also { it.completeExceptionally(error) }
}

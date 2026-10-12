package com.shilapi.xcertplay.media

import android.content.Context
import java.util.concurrent.CompletableFuture

data class AmbientLampSnapshot(
    val frontColor: Int,
    val backColor: Int,
    val frontBrightness: Int,
    val backBrightness: Int,
    val area: Int,
) {
    fun valid(): Boolean = frontColor in 1..31 && backColor in 1..31 &&
        frontBrightness in 0..6 && backBrightness in 0..6 && area in 1..3
}

interface AmbientLightingClient : AutoCloseable {
    fun unhealthy(): Boolean
    fun begin(seed: AmbientLampSnapshot?): CompletableFuture<AmbientLampSnapshot>
    fun apply(area: Int, color: Int, brightness: Int): CompletableFuture<Unit>
    fun stop(): CompletableFuture<Unit>
    override fun close()
}

interface AmbientLightingProvider {
    fun create(context: Context): AmbientLightingClient
    fun checkSupport(context: Context): CompletableFuture<Boolean>
}

package com.lllhys.geeplay.vehicle.geely

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import com.shilapi.xcertplay.navigation.apple.AppleNavigationSnapshot
import com.shilapi.xcertplay.navigation.runtime.NavigationClear
import com.shilapi.xcertplay.navigation.runtime.NavigationOutput
import com.shilapi.xcertplay.navigation.runtime.NavigationPublication
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Conflated, non-blocking route output to a separately started GeePlay gcp/1 daemon. */
internal class GeelyQnxTurnOutput(
    private val config: GeelyQnxWindowConfig,
    private val x: Int,
    private val y: Int,
    private val width: Int,
    private val height: Int,
) : NavigationOutput {
    private val latest = AtomicReference<AppleNavigationSnapshot?>(null)
    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "geeplay-qnx-navigation").apply { isDaemon = true }
    }
    @Volatile private var stopped = false
    private var connection: GeelyQnxConnection? = null
    private var previous: AppleNavigationSnapshot? = null
    private var retryAfter = 0L

    init {
        require(config.enabled)
        GeelyQnxStatus.update("turns", "waiting_for_route")
        GeelyQnxProtocol.hello(x, y, width, height)
        worker.scheduleWithFixedDelay(::tick, 0, 500, TimeUnit.MILLISECONDS)
    }

    override fun publish(publication: NavigationPublication) { if (!stopped) latest.set(publication.snapshot) }
    override fun clear(clear: NavigationClear) { latest.set(null) }

    private fun tick() {
        if (stopped) return
        val snapshot = latest.get()
        if (snapshot == null) {
            GeelyQnxStatus.update("turns", "waiting_for_route")
            closeConnection()
            return
        }
        if (connection == null && System.nanoTime() < retryAfter) return
        try {
            val link = connection ?: GeelyQnxConnection.open(config, x, y, width, height).also {
                connection = it
                GeelyQnxStatus.update("turns", "connected")
                previous = null
            }
            if (snapshot != previous) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD }
                    paint.textSize = height * 0.64f
                    canvas.drawText(maneuverSymbol(snapshot.maneuver.type), width * 0.04f, height * 0.73f, paint)
                    paint.textSize = height * 0.28f
                    canvas.drawText(distanceLabel(snapshot.nextDistanceMeters), width * 0.35f, height * 0.43f, paint)
                    paint.textSize = height * 0.18f
                    val road = snapshot.maneuver.afterRoad.ifBlank { snapshot.currentRoad }
                    canvas.save(); canvas.clipRect(width * 0.35f, height * 0.52f, width.toFloat(), height.toFloat())
                    canvas.drawText(road, width * 0.35f, height * 0.79f, paint); canvas.restore()
                    val pixels = ByteArray(width * height * 4)
                    bitmap.copyPixelsToBuffer(ByteBuffer.wrap(pixels))
                    if (!stopped && latest.get() === snapshot) link.frame(width, height, pixels)
                } finally { bitmap.recycle() }
                previous = snapshot
            } else link.ping()
        } catch (error: Exception) {
            GeelyQnxStatus.update("turns", "disconnected:${error.javaClass.simpleName}")
            Log.w("GeePlay-QNX", "Navigation window disconnected: ${error.javaClass.simpleName}")
            closeConnection()
            retryAfter = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        }
    }

    private fun closeConnection() {
        runCatching { connection?.close() }
        connection = null
        previous = null
    }

    @Synchronized override fun close() {
        if (stopped) return
        stopped = true
        GeelyQnxStatus.update("turns", "closed")
        latest.set(null)
        worker.execute { closeConnection() }
        worker.shutdown()
    }
}

/** Unknown and compound manoeuvres remain blank rather than presenting a false direction. */
internal fun maneuverSymbol(type: Int): String = when (type) {
    1, 20, 47 -> "←"
    2, 21, 48 -> "→"
    13, 22, 49, 52 -> "↖"
    14, 23, 50, 53 -> "↗"
    3, 5, 8, 9, 11, 51 -> "↑"
    else -> ""
}

internal fun distanceLabel(meters: Int): String = if (meters < 1000) "${meters.coerceAtLeast(0)} m"
    else String.format(java.util.Locale.ROOT, "%.1f km", meters / 1000.0)

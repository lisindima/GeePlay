package com.lllhys.geeplay.vehicle.geely

import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Sends only the most recent decoded frame; network I/O never blocks the decoder. */
internal class GeelyQnxMapOutput(
    private val config: GeelyQnxWindowConfig,
    private val x: Int,
    private val y: Int,
    private val width: Int,
    private val height: Int,
) : Closeable {
    private val pending = AtomicReference<ByteArray?>(null)
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "geeplay-qnx-map").apply { isDaemon = true }
    }
    private var link: GeelyQnxConnection? = null
    private var retryAfter = 0L
    private var frames = 0L
    @Volatile private var closed = false

    init {
        require(config.enabled)
        GeelyQnxStatus.update("map", "waiting_for_video")
        GeelyQnxProtocol.hello(x, y, width, height)
        worker.scheduleWithFixedDelay(::sendLatest, 0, 200, TimeUnit.MILLISECONDS)
    }

    /** Takes ownership of the frame. Frames must have exactly window dimensions. */
    fun offer(frame: ByteArray) {
        require(frame.size.toLong() == width.toLong() * height * 4)
        if (!closed) pending.set(frame)
    }

    private fun sendLatest() {
        if (closed) return
        val frame = pending.getAndSet(null)
        if (link == null && (frame == null || System.nanoTime() < retryAfter)) return
        try {
            val connection = link ?: GeelyQnxConnection.open(config, x, y, width, height).also { link = it; GeelyQnxStatus.update("map", "connected") }
            if (frame == null) connection.ping() else {
                connection.frame(width, height, frame)
                GeelyQnxStatus.update("map", "connected:frames=${++frames}")
            }
        } catch (error: Exception) {
            GeelyQnxStatus.update("map", "disconnected:${error.javaClass.simpleName}")
            runCatching { link?.close() }
            link = null
            retryAfter = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        GeelyQnxStatus.update("map", "closed")
        pending.set(null)
        worker.execute { runCatching { link?.close() }; link = null }
        worker.shutdown()
    }
}

/** Copies an RGBA plane while discarding row padding; preserves the buffer's current position. */
internal fun copyRgbaPlane(source: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): ByteArray {
    return scaleRgbaPlane(source, width, height, rowStride, pixelStride, width, height)
}

/** Nearest-neighbour scaling is bounded by the QNX window rather than the decoded stream size. */
internal fun scaleRgbaPlane(source: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int,
                            targetWidth: Int, targetHeight: Int): ByteArray {
    require(width in 1..4096 && height in 1..4096)
    require(targetWidth in 1..1920 && targetHeight in 1..532)
    require(pixelStride >= 4 && rowStride.toLong() >= (width - 1L) * pixelStride + 4)
    val start = source.position()
    val required = (height - 1L) * rowStride + (width - 1L) * pixelStride + 4
    require(required <= source.remaining())
    val output = ByteArray(targetWidth * targetHeight * 4)
    for (y in 0 until targetHeight) for (x in 0 until targetWidth) {
        val offset = start + (y * height / targetHeight) * rowStride + (x * width / targetWidth) * pixelStride
        val destination = (y * targetWidth + x) * 4
        for (channel in 0..3) output[destination + channel] = source.get(offset + channel)
    }
    return output
}

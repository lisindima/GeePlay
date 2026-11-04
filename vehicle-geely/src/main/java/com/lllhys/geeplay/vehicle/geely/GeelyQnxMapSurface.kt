package com.lllhys.geeplay.vehicle.geely

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Experimental decoder target for a map-sized QNX window. Not attached to CarPlay yet. */
class GeelyQnxMapSurface(
    config: GeelyQnxWindowConfig,
    x: Int,
    y: Int,
    private val width: Int,
    private val height: Int,
    streamWidth: Int,
    streamHeight: Int,
) : com.shilapi.xcertplay.vehicle.VehicleMapMirror {
    private val closed = AtomicBoolean()
    private val output = GeelyQnxMapOutput(config, x, y, width, height)
    private val thread = HandlerThread("geeplay-qnx-map-reader").apply { start() }
    private val reader = ImageReader.newInstance(streamWidth, streamHeight, PixelFormat.RGBA_8888, 2)
    private var lastFrameAt = 0L
    override val surface: Surface get() = reader.surface

    init {
        reader.setOnImageAvailableListener({ source ->
            if (!closed.get()) {
                runCatching {
                    source.acquireLatestImage()?.use { image ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastFrameAt < 200) return@use
                        lastFrameAt = now
                        val plane = image.planes.single()
                        output.offer(scaleRgbaPlane(plane.buffer, image.width, image.height,
                            plane.rowStride, plane.pixelStride, width, height))
                    }
                }
            }
        }, Handler(thread.looper))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        reader.setOnImageAvailableListener(null, null)
        reader.close()
        thread.quitSafely()
        output.close()
    }
}

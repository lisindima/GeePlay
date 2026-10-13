package com.shilapi.xcertplay.media

import java.io.Closeable

/** Decodes one complete Opus access unit into little-endian signed 16-bit PCM. */
internal interface OpusPacketDecoder : Closeable {
    val implementation: String

    /** Returns the PCM byte count written to [output], or a negative Opus error code. */
    fun decode(packet: ByteArray, output: ByteArray): Int
}

/**
 * Chooses the same bundled libopus backend shipped by the validated DiPlay build, with the already
 * vendored Concentus implementation as an ABI-independent fallback.
 */
internal object OpusPacketDecoders {
    fun create(
        sampleRate: Int,
        channels: Int,
        onError: (String, Throwable) -> Unit = { _, _ -> },
        native: () -> OpusPacketDecoder? = {
            NativeOpusBackend.create(sampleRate, channels, onError)
        },
        software: () -> OpusPacketDecoder? = {
            SoftwareOpusDecoder.create(sampleRate, channels, onError)
        },
    ): OpusPacketDecoder? {
        native()?.let { return it }
        return software()
    }
}

/** JNI wrapper for the libopus decoder bundled in the validated DiPlay 0.2.13 package. */
internal class NativeOpusBackend private constructor(
    private var handle: Long,
    private val channels: Int,
) : OpusPacketDecoder {
    override val implementation: String = "bundled-libopus"

    @Synchronized
    override fun decode(packet: ByteArray, output: ByteArray): Int {
        val activeHandle = handle
        if (activeHandle == 0L || packet.isEmpty()) return 0
        require(output.size >= MAX_FRAME_BYTES_PER_CHANNEL * channels) {
            "Opus PCM output must hold a 120 ms frame"
        }
        return nativeDecode(activeHandle, packet, output)
    }

    @Synchronized
    override fun close() {
        val activeHandle = handle
        handle = 0L
        if (activeHandle != 0L) nativeDestroy(activeHandle)
    }

    private external fun nativeSelfTest(): Boolean
    private external fun nativeCreate(sampleRate: Int, channels: Int): Long
    private external fun nativeDecode(handle: Long, packet: ByteArray, output: ByteArray): Int
    private external fun nativeDestroy(handle: Long)

    internal companion object {
        const val MAX_FRAME_SAMPLES = 5_760
        const val MAX_FRAME_BYTES_PER_CHANNEL = MAX_FRAME_SAMPLES * 2

        private val libraryLoaded: Result<Boolean> by lazy {
            runCatching {
                System.loadLibrary("diplay_opus_jni")
                NativeOpusBackend(0L, 1).nativeSelfTest()
            }
        }

        fun create(
            sampleRate: Int,
            channels: Int,
            onError: (String, Throwable) -> Unit = { _, _ -> },
        ): NativeOpusBackend? {
            require(channels in 1..2) { "Opus channel count must be one or two" }
            val ready = libraryLoaded.getOrElse {
                onError("bundled libopus unavailable", it)
                return null
            }
            if (!ready) {
                onError("bundled libopus self-test failed", IllegalStateException("native self-test returned false"))
                return null
            }
            val probe = NativeOpusBackend(0L, channels)
            val handle = runCatching { probe.nativeCreate(sampleRate, channels) }.getOrElse {
                onError("bundled libopus decoder creation failed", it)
                return null
            }
            if (handle == 0L) {
                onError("bundled libopus decoder creation failed", IllegalStateException("native handle is zero"))
                return null
            }
            return NativeOpusBackend(handle, channels)
        }
    }
}

/** Pure-Java fallback used when a packaged native ABI cannot be loaded. */
internal class SoftwareOpusDecoder private constructor(
    private var decoder: org.concentus.OpusDecoder?,
    private val channels: Int,
    private val onError: (String, Throwable) -> Unit,
) : OpusPacketDecoder {
    override val implementation: String = "concentus"
    private val decoded = ShortArray(NativeOpusBackend.MAX_FRAME_SAMPLES * channels)
    private var failures = 0

    override fun decode(packet: ByteArray, output: ByteArray): Int {
        val activeDecoder = decoder ?: return 0
        if (packet.isEmpty()) return 0
        return try {
            val frames = activeDecoder.decode(
                packet,
                0,
                packet.size,
                decoded,
                0,
                NativeOpusBackend.MAX_FRAME_SAMPLES,
                false,
            )
            val samples = frames * channels
            val bytes = samples * 2
            require(bytes <= output.size) { "Opus decoder returned more PCM than the output can hold" }
            for (index in 0 until samples) {
                val sample = decoded[index].toInt()
                output[index * 2] = sample.toByte()
                output[index * 2 + 1] = (sample ushr 8).toByte()
            }
            bytes
        } catch (error: Throwable) {
            if (failures++ < MAX_REPORTED_FAILURES) onError("software Opus decode failed", error)
            -1
        }
    }

    override fun close() {
        decoder = null
    }

    internal companion object {
        private const val MAX_REPORTED_FAILURES = 3

        fun create(
            sampleRate: Int,
            channels: Int,
            onError: (String, Throwable) -> Unit = { _, _ -> },
        ): SoftwareOpusDecoder? = try {
            SoftwareOpusDecoder(org.concentus.OpusDecoder(sampleRate, channels), channels, onError)
        } catch (error: Throwable) {
            onError("software Opus decoder unavailable", error)
            null
        }
    }
}

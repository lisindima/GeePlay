package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OpusPacketDecoderTest {
    @Test
    fun nativeDecoderIsPreferredWhenBundledBackendIsAvailable() {
        val native = FakeDecoder("native")
        var softwareCreated = false

        val selected = OpusPacketDecoders.create(
            sampleRate = 48_000,
            channels = 1,
            native = { native },
            software = { softwareCreated = true; FakeDecoder("software") },
        )

        assertSame(native, selected)
        assertTrue(!softwareCreated)
    }

    @Test
    fun softwareDecoderIsUsedWhenNativeAbiIsUnavailable() {
        val software = FakeDecoder("software")

        val selected = OpusPacketDecoders.create(
            sampleRate = 48_000,
            channels = 1,
            native = { null },
            software = { software },
        )

        assertSame(software, selected)
    }

    @Test
    fun bundledSoftwareDecoderProducesPcmForARealOpusPacket() {
        val encoder = SoftwareOpusEncoder(bitrate = 48_000)
        val decoder = SoftwareOpusDecoder.create(48_000, 1)
        assertNotNull(decoder)
        val packet = encoder.encode(ByteArray(SoftwareOpusEncoder.FRAME_BYTES)).single()
        val pcm = ByteArray(NativeOpusBackend.MAX_FRAME_BYTES_PER_CHANNEL)

        val bytes = decoder!!.decode(packet, pcm)

        assertEquals(SoftwareOpusEncoder.FRAME_BYTES, bytes)
        decoder.close()
        encoder.close()
    }

    private class FakeDecoder(override val implementation: String) : OpusPacketDecoder {
        override fun decode(packet: ByteArray, output: ByteArray): Int = 0
        override fun close() = Unit
    }
}

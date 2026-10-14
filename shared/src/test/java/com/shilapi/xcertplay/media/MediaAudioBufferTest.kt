package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaAudioBufferTest {
    @Test
    fun `low latency presets keep only a small PCM headroom while compressed packets absorb bursts`() {
        assertEquals(listOf(50, 100, 300, 500, 1000), MediaAudioBuffer.presets)

        val experimental = MediaAudioBuffer.plan(true, 48_000, 2, minBufferBytes = 7_680, mediaMillis = 50)
        assertEquals(9_600, experimental.startBytes)
        assertEquals(13_440, experimental.trackBufferBytes)

        val lowLatency = MediaAudioBuffer.plan(true, 48_000, 2, minBufferBytes = 7_680, mediaMillis = 100)
        assertEquals(19_200, lowLatency.startBytes)
        assertEquals(23_040, lowLatency.trackBufferBytes)
    }

    @Test
    fun `music buffers the chosen delay plus headroom`() {
        // 48 kHz stereo 16-bit = 192 000 bytes/s.
        val plan = MediaAudioBuffer.plan(true, 48_000, 2, minBufferBytes = 7_680, mediaMillis = 500)
        assertEquals(96_000, plan.startBytes)
        assertEquals(134_400, plan.trackBufferBytes)
    }

    @Test
    fun `all streams mapped to media receive the music buffer`() {
        val selection = AudioChannelMapper.map(
            "compatibility", 100, AudioChannelMappingMode.AUTOMOTIVE_BUS,
        )
        val plan = MediaAudioBuffer.plan(selection.channel == AudioChannel.MEDIA,
            48_000, 2, minBufferBytes = 7_680, mediaMillis = 500)
        assertEquals(96_000, plan.startBytes)
    }

    @Test
    fun `calls and prompts keep the low-latency buffer`() {
        val plan = MediaAudioBuffer.plan(false, 16_000, 1, minBufferBytes = 1_280, mediaMillis = 1000)
        assertEquals(4 * 1024, plan.startBytes)
        assertEquals(16 * 1024, plan.trackBufferBytes)
        val guidance = AudioChannelMapper.map("default", 100, AudioChannelMappingMode.AUTOMOTIVE_BUS)
        assertEquals(plan, MediaAudioBuffer.plan(guidance.channel == AudioChannel.MEDIA,
            16_000, 1, minBufferBytes = 1_280, mediaMillis = 1000))
    }

    @Test
    fun `unknown delay falls back to the default`() {
        assertEquals(50, MediaAudioBuffer.sanitize(50))
        assertEquals(100, MediaAudioBuffer.sanitize(100))
        assertEquals(MediaAudioBuffer.DEFAULT_MILLIS, MediaAudioBuffer.sanitize(250))
        assertEquals(57_600, MediaAudioBuffer.plan(true, 48_000, 2, 7_680, mediaMillis = 42).startBytes)
    }

    @Test
    fun `low latency mode tolerates normal bursts but drops a growing stale backlog`() {
        assertEquals(false, MediaAudioBuffer.shouldDropOldestPacket(100, 48_000, 0, 24_000))
        assertEquals(true, MediaAudioBuffer.shouldDropOldestPacket(100, 48_000, 0, 24_001))
        assertEquals(false, MediaAudioBuffer.shouldDropOldestPacket(50, 48_000, 0, 21_600))
        assertEquals(true, MediaAudioBuffer.shouldDropOldestPacket(50, 48_000, 0, 21_601))
        assertEquals(false, MediaAudioBuffer.shouldDropOldestPacket(300, 48_000, 0, 48_000))
    }

    @Test
    fun `start level stays below a smaller granted buffer`() {
        assertEquals(98_000, MediaAudioBuffer.startBytesFor(192_000, 100_000, 2_048 - 48))
        assertEquals(57_600, MediaAudioBuffer.startBytesFor(57_600, 134_400, 2_048))
        assertEquals(57_600, MediaAudioBuffer.startBytesFor(57_600, 0, 2_048))
    }
}

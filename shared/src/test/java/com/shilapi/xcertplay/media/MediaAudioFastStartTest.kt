package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAudioFastStartTest {
    @Test
    fun `prewarming is opt in and limited to low latency AAC media`() {
        val music = AudioFormat(AudioCodecKind.AAC_LC, 48_000, 2, 102, "media")
        assertFalse(MediaAudioFastStart.shouldPrewarm(false, music, 50))
        assertTrue(MediaAudioFastStart.shouldPrewarm(true, music, 50))
        assertTrue(MediaAudioFastStart.shouldPrewarm(true, music, 100))
        assertFalse(MediaAudioFastStart.shouldPrewarm(true, music, 300))
        assertFalse(MediaAudioFastStart.shouldPrewarm(
            true,
            AudioFormat(AudioCodecKind.OPUS, 48_000, 1, 101, "default"),
            50,
        ))
    }

    @Test
    fun `experimental AAC playback starts after one decoded frame`() {
        val music = AudioFormat(AudioCodecKind.AAC_LC, 48_000, 2, 102, "media")
        val planned = MediaAudioBuffer.plan(true, 48_000, 2, minBufferBytes = 12_288, mediaMillis = 50)
        assertEquals(12_288, MediaAudioFastStart.startThresholdBytes(false, music, 50, planned.startBytes))
        assertEquals(4_096, MediaAudioFastStart.startThresholdBytes(true, music, 50, planned.startBytes))
        assertEquals(12_288, MediaAudioFastStart.startThresholdBytes(true, music, 300, planned.startBytes))
    }
}

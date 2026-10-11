package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaAudioPolicyTest {
    @Test
    fun `OEM Bluetooth suppresses only media-classified streams`() {
        val policy = LocalMediaAudioPolicy.OEM_BLUETOOTH

        assertFalse(policy.shouldRender(format(100, "media")))
        assertFalse(policy.shouldRender(format(102, "media")))
        assertFalse(policy.shouldRender(format(102, "unknown")))
        assertTrue(policy.shouldRender(format(100, "default")))
        assertTrue(policy.shouldRender(format(100, "alert")))
        assertTrue(policy.shouldRender(format(100, "speechRecognition")))
        assertTrue(policy.shouldRender(format(100, "telephony")))
        assertTrue(policy.advertiseAudio)
        assertFalse(policy.ownMediaSession)
        assertTrue(policy.retainWirelessBootstrap)
    }

    @Test
    fun `DiPlay owner renders every stream and owns media controls`() {
        val policy = LocalMediaAudioPolicy.DIPLAY

        assertTrue(policy.shouldRender(format(100, "media")))
        assertTrue(policy.shouldRender(format(100, "telephony")))
        assertTrue(policy.ownMediaSession)
        assertFalse(policy.retainWirelessBootstrap)
    }

    @Test
    fun `external owner removes the complete AirPlay audio offer`() {
        val policy = LocalMediaAudioPolicy.PHONE_OR_EXTERNAL

        assertFalse(policy.advertiseAudio)
        assertFalse(policy.shouldRender(format(100, "media")))
        assertTrue(policy.shouldRender(format(100, "telephony")))
    }

    private fun format(type: Int, audioType: String) =
        AudioFormat(AudioCodecKind.LPCM, 48_000, 2, type, audioType)
}

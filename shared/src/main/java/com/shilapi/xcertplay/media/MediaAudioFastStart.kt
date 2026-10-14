package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat

/** Guardrails for the opt-in path that trades music startup resilience for latency. */
internal object MediaAudioFastStart {
    fun shouldPrewarm(enabled: Boolean, format: AudioFormat, mediaBufferMillis: Int): Boolean =
        enabled &&
            format.audioType == "media" &&
            format.codec == AudioCodecKind.AAC_LC &&
            MediaAudioBuffer.sanitize(mediaBufferMillis) < MediaAudioBuffer.DEFAULT_MILLIS

    fun startThresholdBytes(
        enabled: Boolean,
        format: AudioFormat,
        mediaBufferMillis: Int,
        plannedStartBytes: Int,
    ): Int = if (shouldPrewarm(enabled, format, mediaBufferMillis)) {
        AAC_FRAMES_PER_PACKET * format.channels.coerceIn(1, 2) * PCM_16_BIT_BYTES
    } else {
        plannedStartBytes
    }

    private const val AAC_FRAMES_PER_PACKET = 1024
    private const val PCM_16_BIT_BYTES = 2
}

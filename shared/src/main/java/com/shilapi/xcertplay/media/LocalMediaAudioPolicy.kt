package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioFormat

enum class MediaOutputOwner {
    DIPLAY,
    OEM_BLUETOOTH,
    PHONE_OR_EXTERNAL,
}

/** Immutable policy captured for one CarPlay connection. */
data class LocalMediaAudioPolicy(
    val owner: MediaOutputOwner,
    val advertiseAudio: Boolean,
    val renderMusicLocally: Boolean,
    val ownMediaSession: Boolean,
    val retainWirelessBootstrap: Boolean,
) {
    fun shouldRender(format: AudioFormat): Boolean =
        renderMusicLocally ||
            AudioChannelMapper.map(
                format.audioType,
                format.payloadType,
                AudioChannelMappingMode.MOBILE_COMPATIBLE,
            ).channel != AudioChannel.MEDIA

    companion object {
        val DIPLAY = LocalMediaAudioPolicy(
            owner = MediaOutputOwner.DIPLAY,
            advertiseAudio = true,
            renderMusicLocally = true,
            ownMediaSession = true,
            retainWirelessBootstrap = false,
        )
        val OEM_BLUETOOTH = LocalMediaAudioPolicy(
            owner = MediaOutputOwner.OEM_BLUETOOTH,
            advertiseAudio = true,
            renderMusicLocally = false,
            ownMediaSession = false,
            retainWirelessBootstrap = true,
        )
        val PHONE_OR_EXTERNAL = LocalMediaAudioPolicy(
            owner = MediaOutputOwner.PHONE_OR_EXTERNAL,
            advertiseAudio = false,
            renderMusicLocally = false,
            ownMediaSession = false,
            retainWirelessBootstrap = true,
        )
    }
}

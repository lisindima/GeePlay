package com.shilapi.xcertplay.media

/**
 * Jitter buffer for streams mapped to the media channel. Wireless CarPlay delivers audio over the same Wi-Fi link as
 * video; radio gaps of several hundred milliseconds are normal, so music needs a buffer that
 * outlasts them. Calls, Siri and navigation prompts keep the small low-latency buffer.
 */
object MediaAudioBuffer {
    const val DEFAULT_MILLIS = 300
    val presets = listOf(50, 100, DEFAULT_MILLIS, 500, 1000)

    private const val STABLE_HEADROOM_MILLIS = 200
    private const val LOW_LATENCY_PCM_HEADROOM_MILLIS = 20
    private const val LOW_LATENCY_MIN_WRITE_HEADROOM_BYTES = 2 * 1024
    private const val MIN_TRACK_BUFFER_BYTES = 16 * 1024
    private const val MIN_START_BUFFER_BYTES = 4 * 1024

    fun sanitize(millis: Int): Int = millis.takeIf { it in presets } ?: DEFAULT_MILLIS

    /**
     * Stable presets pause and refill after an underrun. Low-latency presets keep the track
     * running so bursty wireless delivery cannot add one more prebuffer delay on every gap.
     */
    fun shouldRebufferAfterUnderrun(millis: Int): Boolean = sanitize(millis) >= DEFAULT_MILLIS

    /**
     * Low-latency playback prefers skipping stale compressed music after a wireless gap over
     * preserving an ever-growing delay. Stable presets retain every packet.
     */
    fun shouldDropOldestPacket(
        millis: Int,
        rtpClockRate: Int,
        oldestSample: Int,
        newestSample: Int,
    ): Boolean {
        val targetMillis = sanitize(millis)
        if (targetMillis >= DEFAULT_MILLIS || rtpClockRate <= 0) return false
        val queuedSamples = (newestSample.toLong() - oldestSample.toLong()) and 0xffff_ffffL
        // Wireless CarPlay commonly sends roughly 400 ms ahead in a burst and then goes quiet.
        // Preserve that normal jitter window; only trim delay accumulated beyond it.
        val targetSamples = rtpClockRate.toLong() *
            (targetMillis + WIRELESS_BURST_TOLERANCE_MILLIS) / 1000L
        val largestExpectedQueue = rtpClockRate.toLong() * 10L
        return queuedSamples > targetSamples && queuedSamples <= largestExpectedQueue
    }

    data class Plan(val trackBufferBytes: Int, val startBytes: Int)

    private const val WIRELESS_BURST_TOLERANCE_MILLIS = 400

    /** AudioTrack capacity and the amount to queue before play() for one output stream. */
    fun plan(isMedia: Boolean, sampleRate: Int, channels: Int, minBufferBytes: Int, mediaMillis: Int): Plan {
        val lowLatency = Plan(
            trackBufferBytes = maxOf(minBufferBytes * 4, MIN_TRACK_BUFFER_BYTES),
            startBytes = maxOf(minBufferBytes, MIN_START_BUFFER_BYTES),
        )
        if (!isMedia) return lowLatency
        val bytesPerSecond = sampleRate.toLong() * channels.coerceIn(1, 2) * 2
        val selectedMillis = sanitize(mediaMillis)
        val start = maxOf(
            (bytesPerSecond * selectedMillis / 1000).toInt(),
            lowLatency.startBytes,
        )
        if (selectedMillis < DEFAULT_MILLIS) {
            // Compressed AAC packets already absorb the ~400 ms wireless burst pattern. Keeping
            // that same headroom as decoded PCM adds audible output latency, so low-latency modes
            // retain only enough AudioTrack space for one in-flight blocking write.
            val capacity = (bytesPerSecond *
                (selectedMillis + LOW_LATENCY_PCM_HEADROOM_MILLIS) / 1000).toInt()
            return Plan(
                trackBufferBytes = maxOf(
                    capacity,
                    minBufferBytes,
                    start + LOW_LATENCY_MIN_WRITE_HEADROOM_BYTES,
                ),
                startBytes = start,
            )
        }
        val capacity = (bytesPerSecond * (selectedMillis + STABLE_HEADROOM_MILLIS) / 1000).toInt()
        return Plan(
            trackBufferBytes = maxOf(capacity, lowLatency.trackBufferBytes),
            startBytes = start,
        )
    }

    /**
     * The device may grant a smaller AudioTrack than requested. Writes block while the track is
     * paused and full, so the start level must stay below the real capacity or play() never runs.
     */
    fun startBytesFor(plannedStartBytes: Int, actualCapacityBytes: Int, writeChunkBytes: Int): Int {
        if (actualCapacityBytes <= 0) return plannedStartBytes
        return minOf(plannedStartBytes, actualCapacityBytes - writeChunkBytes).coerceAtLeast(writeChunkBytes)
    }
}

package com.shilapi.xcertplay.media

/** Music alone can trade latency for resilience to the TS7's shared 2.4 GHz radio stalls. */
internal class Ts7MusicBuffer(sampleRate: Int, frameBytes: Int, capacityBytes: Int, initialMillis: Int) {
    private val bytesPerMillis = sampleRate.toLong() * frameBytes / 1000
    private val ceiling = minOf(MAX_MILLIS.toLong() * bytesPerMillis,
        (capacityBytes - WRITE_CHUNK_BYTES).coerceAtLeast(WRITE_CHUNK_BYTES).toLong()).toInt()
    private val floor = minOf(ceiling, (maxOf(INITIAL_MILLIS, initialMillis) * bytesPerMillis).toInt())
    var targetBytes = floor
        private set
    private var stableSinceNs = 0L

    fun rebuffer(nowNs: Long) {
        targetBytes = minOf(ceiling, targetBytes + (STEP_MILLIS * bytesPerMillis).toInt())
        stableSinceNs = nowNs
    }

    fun stable(nowNs: Long): Boolean {
        if (stableSinceNs == 0L) stableSinceNs = nowNs
        if (nowNs - stableSinceNs < DECAY_NS || targetBytes <= floor) return false
        targetBytes = maxOf(floor, targetBytes - (DECAY_MILLIS * bytesPerMillis).toInt())
        stableSinceNs = nowNs
        return true
    }

    companion object {
        const val INITIAL_MILLIS = 900
        const val MAX_MILLIS = 2200
        const val TRACK_MILLIS = 2600
        const val STEP_MILLIS = 300
        const val DECAY_MILLIS = 100
        const val DECAY_NS = 60_000_000_000L
        const val WRITE_CHUNK_BYTES = 4096
        fun enabled(ts7: Boolean, audioType: String) = ts7 && audioType == "media"
    }
}

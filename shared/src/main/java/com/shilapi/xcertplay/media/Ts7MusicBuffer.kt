package com.shilapi.xcertplay.media

/** Music alone can trade latency for resilience to the TS7's shared 2.4 GHz radio stalls. */
internal class Ts7MusicBuffer(sampleRate: Int, frameBytes: Int, capacityBytes: Int, initialMillis: Int) {
    private val bytesPerMillis = sampleRate.toLong() * frameBytes / 1000
    private val writeChunkBytes = maxOf(frameBytes, (bytesPerMillis * MAX_WRITE_MILLIS).toInt())
    private val capacity = capacityBytes.coerceAtLeast(writeChunkBytes)
    private val ceiling = minOf(MAX_TARGET_MILLIS.toLong() * bytesPerMillis,
        (capacity - writeChunkBytes).coerceAtLeast(writeChunkBytes).toLong()).toInt()
    private val floor = minOf(ceiling, (maxOf(INITIAL_MILLIS, initialMillis) * bytesPerMillis).toInt())
    var targetBytes = floor
        private set
    private var stableSinceNs = 0L

    val targetMillis: Int
        get() = if (bytesPerMillis > 0) (targetBytes / bytesPerMillis).toInt() else 0

    /** The active AudioTrack ring and the compressed RTP queue share this total limit. */
    val totalLimitBytes: Int
        get() = minOf((queueLimitMillis(targetMillis) * bytesPerMillis).toInt(), capacity)

    fun rebuffer(nowNs: Long) {
        targetBytes = minOf(ceiling, targetBytes + (STEP_MILLIS * bytesPerMillis).toInt())
        stableSinceNs = nowNs
    }

    fun reset() {
        targetBytes = floor
        stableSinceNs = 0L
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
        const val MAX_TARGET_MILLIS = 2200
        const val MAX_QUEUE_MILLIS = 2400
        const val MIN_QUEUE_MILLIS = 2100
        const val QUEUE_HEADROOM_MILLIS = 1200
        const val PLAYBACK_DROP_GRACE_MILLIS = 800
        const val PLAYBACK_DROP_DELAY_NS = 10_000_000_000L
        const val MAX_WRITE_MILLIS = 40
        const val STEP_MILLIS = 300
        const val DECAY_MILLIS = 100
        const val DECAY_NS = 60_000_000_000L
        fun queueLimitMillis(targetMs: Int): Int =
            (targetMs + QUEUE_HEADROOM_MILLIS).coerceIn(MIN_QUEUE_MILLIS, MAX_QUEUE_MILLIS)

        fun enabled(ts7: Boolean, audioType: String) = ts7 && audioType == "media"
    }
}

/** Applies the ten-second hysteresis before dropping whole queued music packets during playback. */
internal class Ts7MusicLatencyPolicy {
    private var aboveLimitSinceNs = 0L
    private var trimming = false

    fun shouldDropBeforePlayback(totalQueuedMs: Long, targetMs: Int): Boolean =
        totalQueuedMs > Ts7MusicBuffer.queueLimitMillis(targetMs)

    fun shouldDropDuringPlayback(totalQueuedMs: Long, targetMs: Int, nowNs: Long): Boolean {
        val limit = Ts7MusicBuffer.queueLimitMillis(targetMs).toLong()
        if (trimming) {
            if (totalQueuedMs <= limit) {
                trimming = false
                aboveLimitSinceNs = 0L
                return false
            }
            return true
        }
        if (totalQueuedMs <= limit + Ts7MusicBuffer.PLAYBACK_DROP_GRACE_MILLIS) {
            aboveLimitSinceNs = 0L
            return false
        }
        if (aboveLimitSinceNs == 0L) aboveLimitSinceNs = nowNs
        if (nowNs - aboveLimitSinceNs < Ts7MusicBuffer.PLAYBACK_DROP_DELAY_NS) return false
        trimming = true
        return totalQueuedMs > limit
    }

    fun reset() {
        aboveLimitSinceNs = 0L
        trimming = false
    }
}

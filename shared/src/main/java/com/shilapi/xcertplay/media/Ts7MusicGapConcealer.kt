package com.shilapi.xcertplay.media

/** Only authenticated AAC RTP on the TS7 uses this deadline: decoded later packets commit the timeline. */
internal class Ts7MusicGapConcealer(private val sampleRate: Int) {
    private var previousSequence: Int? = null
    private var previousSample = 0
    private val pending = LinkedHashMap<Long, Int>()
    var missingPackets = 0L
        private set
    var lostBursts = 0L
        private set
    var lateOrDuplicate = 0L
        private set

    fun accept(sequence: Int, sample: Int): Boolean {
        val previous = previousSequence
        if (previous != null) {
            val distance = (sequence - previous) and 0xffff
            if (distance == 0 || distance >= 0x8000) {
                lateOrDuplicate++
                return false
            }
            val timestampDistance = (sample.toLong() - previousSample) and 0xffff_ffffL
            val missing = distance - 1
            // A timestamp restart is not loss. Only conceal a matching, bounded AAC-LC sequence gap.
            if (missing > 0 && timestampDistance == distance * AAC_FRAMES.toLong()) {
                missingPackets += missing
                lostBursts += missing / BURST_PACKETS
                if (missing * AAC_FRAMES <= sampleRate) {
                    pending[timestampUs(sample)] = missing * AAC_FRAMES
                    if (pending.size > MAX_PENDING) pending.remove(pending.keys.first())
                }
            }
        }
        previousSequence = sequence
        previousSample = sample
        return true
    }

    fun silenceBefore(presentationTimeUs: Long): Int = pending.remove(presentationTimeUs) ?: 0
    private fun timestampUs(sample: Int) = (sample.toLong() and 0xffff_ffffL) * 1_000_000L / sampleRate

    companion object {
        const val AAC_FRAMES = 1024
        const val BURST_PACKETS = 18
        private const val MAX_PENDING = 64
    }
}

/** Receive-thread burst measurements have no packet contents or device identifiers. */
internal class AudioBurstStats {
    private var lastNs: Long? = null
    private var startNs = 0L
    private var count = 0
    private var largest = 0
    private var periodMs = 0L

    @Synchronized fun received(nowNs: Long) {
        val last = lastNs
        if (last == null || nowNs - last > 50_000_000L) {
            if (last != null) periodMs = (nowNs - startNs) / 1_000_000L
            largest = maxOf(largest, count)
            startNs = nowNs
            count = 0
        }
        count++
        lastNs = nowNs
    }

    @Synchronized fun snapshot(): String {
        val result = "burstPacketsMax=${maxOf(largest, count)} burstPeriodMs=$periodMs"
        largest = 0
        return result
    }
}

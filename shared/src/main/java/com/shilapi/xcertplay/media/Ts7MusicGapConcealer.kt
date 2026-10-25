package com.shilapi.xcertplay.media

/** Orders a small RTP reorder window before deciding that an AAC packet was lost. */
internal class Ts7MusicGapConcealer<T>(private val sampleRate: Int) {
    private data class Packet<T>(val sequence: Int, val sample: Int, val value: T, val arrivedNs: Long)

    private var previousSequence: Int? = null
    private var previousSample = 0
    private val pending = LinkedHashMap<Long, Int>()
    private val reordered = LinkedHashMap<Int, Packet<T>>()
    private val restartCandidates = ArrayList<Packet<T>>()
    var missingPackets = 0L
        private set
    var lostBursts = 0L
        private set
    var lateOrDuplicate = 0L
        private set

    val hasReorderedPackets: Boolean get() = reordered.isNotEmpty()
    val reorderedPacketCount: Int get() = reordered.size

    /** Returns packets that can be decoded now, in RTP sequence order. */
    fun accept(sequence: Int, sample: Int, value: T, arrivedNs: Long): List<T> {
        val next = Packet(sequence and 0xffff, sample, value, arrivedNs)
        val previous = previousSequence
        if (previous == null) {
            restartCandidates.clear()
            emit(next)
            return listOf(value)
        }

        val distance = (next.sequence - previous) and 0xffff
        if (distance == 0 || distance >= 0x8000) {
            return acceptLate(next)
        }

        restartCandidates.clear()
        val expected = (previous + 1) and 0xffff
        if (next.sequence == expected) {
            val output = ArrayList<T>()
            emit(next)
            output += next.value
            drainContiguous(output)
            return output
        }

        if (distance <= MAX_REORDER_AHEAD) {
            reordered.putIfAbsent(next.sequence, next)
            return emptyList()
        }

        // A jump beyond the bounded reorder window commits its missing frames now.
        val output = ArrayList<T>()
        drainNearest(output, force = true)
        emit(next)
        output += next.value
        drainContiguous(output)
        return output
    }

    /** Commit packets whose lower sequence numbers did not arrive before the small reorder deadline. */
    fun flushExpired(nowNs: Long): List<T> {
        val output = ArrayList<T>()
        while (reordered.isNotEmpty()) {
            val nearest = nearestPending() ?: break
            if (nowNs - nearest.arrivedNs < REORDER_WAIT_NS) break
            drainNearest(output, force = true)
        }
        return output
    }

    /** Latency trimming deliberately discards this packet, so it must not become a concealed network loss. */
    fun skip(sequence: Int, sample: Int) {
        val normalized = sequence and 0xffff
        reordered.remove(normalized)
        val previous = previousSequence
        if (previous == null || ((normalized - previous) and 0xffff) in 1 until 0x8000) {
            previousSequence = normalized
            previousSample = sample
            pending.remove(timestampUs(sample))
        }
        restartCandidates.clear()
    }

    fun silenceBefore(presentationTimeUs: Long): Int = pending.remove(presentationTimeUs) ?: 0

    /** Forget only the current song's sequence and pending-gap state; diagnostic counters stay cumulative. */
    fun resetTimeline() {
        previousSequence = null
        previousSample = 0
        pending.clear()
        reordered.clear()
        restartCandidates.clear()
    }

    internal fun resetForTest() {
        resetTimeline()
        missingPackets = 0
        lostBursts = 0
        lateOrDuplicate = 0
    }

    private fun acceptLate(packet: Packet<T>): List<T> {
        lateOrDuplicate++
        val lastCandidate = restartCandidates.lastOrNull()
        if (lastCandidate == null || packet.sequence != ((lastCandidate.sequence + 1) and 0xffff)) {
            restartCandidates.clear()
        }
        restartCandidates += packet
        if (restartCandidates.size < RESTART_PACKET_COUNT) return emptyList()

        val restart = restartCandidates.toList()
        lateOrDuplicate = (lateOrDuplicate - restart.size).coerceAtLeast(0)
        reordered.clear()
        pending.clear()
        previousSequence = null
        restartCandidates.clear()
        restart.forEach(::emit)
        return restart.map { it.value }
    }

    private fun drainContiguous(output: MutableList<T>) {
        while (true) {
            val expected = ((previousSequence ?: break) + 1) and 0xffff
            val packet = reordered.remove(expected) ?: break
            emit(packet)
            output += packet.value
        }
    }

    private fun drainNearest(output: MutableList<T>, force: Boolean) {
        val packet = nearestPending() ?: return
        if (!force) return
        reordered.remove(packet.sequence)
        emit(packet)
        output += packet.value
        drainContiguous(output)
    }

    private fun nearestPending(): Packet<T>? {
        val previous = previousSequence ?: return reordered.values.firstOrNull()
        return reordered.values.minByOrNull { (it.sequence - previous) and 0xffff }
    }

    private fun emit(packet: Packet<T>) {
        val previous = previousSequence
        if (previous != null) {
            val distance = (packet.sequence - previous) and 0xffff
            val timestampDistance = (packet.sample.toLong() - previousSample) and 0xffff_ffffL
            val missing = distance - 1
            if (missing > 0 && timestampDistance == distance * AAC_FRAMES.toLong()) {
                missingPackets += missing
                lostBursts += missing / BURST_PACKETS
                if (missing * AAC_FRAMES <= sampleRate) {
                    pending[timestampUs(packet.sample)] = missing * AAC_FRAMES
                    if (pending.size > MAX_PENDING) pending.remove(pending.keys.first())
                }
            }
        }
        previousSequence = packet.sequence
        previousSample = packet.sample
    }

    private fun timestampUs(sample: Int) =
        (sample.toLong() and 0xffff_ffffL) * 1_000_000L / sampleRate

    companion object {
        const val AAC_FRAMES = 1024
        const val BURST_PACKETS = 18
        const val RESTART_PACKET_COUNT = 8
        const val REORDER_WAIT_NS = 30_000_000L
        private const val MAX_REORDER_AHEAD = 3
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

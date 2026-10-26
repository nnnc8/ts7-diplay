package com.shilapi.xcertplay.media

internal enum class AudioTailDrainExitReason(val reportValue: String) {
    PLAYBACK_HEAD_STALLED("playback-head-stalled"),
    TIME_LIMIT("time-limit"),
}

/** Bounds an ended renderer's wait while allowing a moving playback head to drain naturally. */
internal class AudioTailDrainWatch(
    private val stalledHeadMillis: Long = STALLED_HEAD_MILLIS,
    private val extraWaitMillis: Long = EXTRA_WAIT_MILLIS,
) {
    private var startedAtNs: Long? = null
    private var hardWaitNs = 0L
    private var previousHead: Long? = null
    private var lastHeadAdvanceNs = 0L

    fun reset() {
        startedAtNs = null
        hardWaitNs = 0L
        previousHead = null
        lastHeadAdvanceNs = 0L
    }

    fun exitReason(
        nowNs: Long,
        initialQueuedMillis: Long,
        rawPlaybackHead: Int,
        drainReady: Boolean,
    ): AudioTailDrainExitReason? {
        val start = startedAtNs ?: nowNs.also {
            startedAtNs = it
            hardWaitNs = millisToNanosSaturated(
                addSaturated(initialQueuedMillis.coerceAtLeast(0L), extraWaitMillis.coerceAtLeast(0L)),
            )
            previousHead = rawPlaybackHead.toLong() and UINT_MASK
            lastHeadAdvanceNs = it
        }
        val head = rawPlaybackHead.toLong() and UINT_MASK
        val previous = previousHead
        if (previous != head) lastHeadAdvanceNs = nowNs
        previousHead = head

        if (nowNs >= start && nowNs - start >= hardWaitNs) {
            return AudioTailDrainExitReason.TIME_LIMIT
        }
        if (drainReady && nowNs >= lastHeadAdvanceNs &&
            nowNs - lastHeadAdvanceNs >= millisToNanosSaturated(stalledHeadMillis.coerceAtLeast(0L))) {
            return AudioTailDrainExitReason.PLAYBACK_HEAD_STALLED
        }
        return null
    }

    fun waitedMillis(nowNs: Long): Long {
        val start = startedAtNs ?: return 0L
        return if (nowNs >= start) (nowNs - start) / NANOS_PER_MILLI else 0L
    }

    private fun addSaturated(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun millisToNanosSaturated(millis: Long): Long =
        if (millis > Long.MAX_VALUE / NANOS_PER_MILLI) Long.MAX_VALUE else millis * NANOS_PER_MILLI

    private companion object {
        const val STALLED_HEAD_MILLIS = 1_500L
        const val EXTRA_WAIT_MILLIS = 2_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val UINT_MASK = 0xffff_ffffL
    }
}

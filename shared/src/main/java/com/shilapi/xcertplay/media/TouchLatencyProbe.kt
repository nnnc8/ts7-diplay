package com.shilapi.xcertplay.media

/** Diagnostic link between the touch uplink and the next video frame, read by [VideoStats]. */
internal object TouchLatencyProbe {
    @Volatile private var pendingFrameTouchNs = 0L
    @Volatile private var pendingShownTouchNs = 0L
    @Volatile var maxSendNs = 0L

    fun onTouchSent(sentAtNs: Long, sendDurationNs: Long) {
        if (pendingFrameTouchNs == 0L) pendingFrameTouchNs = sentAtNs
        if (pendingShownTouchNs == 0L) pendingShownTouchNs = sentAtNs
        if (sendDurationNs > maxSendNs) maxSendNs = sendDurationNs
    }

    /** Returns touch-to-frame latency for the first frame after a touch, or -1. */
    fun onFrame(nowNs: Long): Long {
        val touch = pendingFrameTouchNs
        if (touch == 0L) return -1
        pendingFrameTouchNs = 0L
        return nowNs - touch
    }

    /** Returns touch-to-surface-release latency for the first shown main-screen frame, or -1. */
    fun onFrameShown(nowNs: Long): Long {
        val touch = pendingShownTouchNs
        if (touch == 0L) return -1
        pendingShownTouchNs = 0L
        return nowNs - touch
    }

    internal fun resetForTest() {
        pendingFrameTouchNs = 0L
        pendingShownTouchNs = 0L
        maxSendNs = 0L
    }
}

package com.shilapi.xcertplay.media

import java.util.Locale

/** Diagnostic link between touch/HID and the next main-screen video frame. */
internal object TouchLatencyProbe {
    @Volatile private var pendingFrameTouchNs = 0L
    @Volatile private var pendingShownTouchNs = 0L
    @Volatile var maxSendNs = 0L

    private val touchEventToHid = LatencySamples()
    private val hidToFrameReceived = LatencySamples()
    private val frameReceivedToDecodeOutput = LatencySamples()
    private val decodeOutputToSurfaceSubmit = LatencySamples()

    /** Compatibility overload for callers that only know when the serialized HID send began. */
    fun onTouchSent(sentAtNs: Long, sendDurationNs: Long) = onTouchSent(0L, sentAtNs, sendDurationNs)

    fun onTouchSent(touchEventAtNs: Long, sentAtNs: Long, sendDurationNs: Long) {
        if (pendingFrameTouchNs == 0L) pendingFrameTouchNs = sentAtNs
        if (pendingShownTouchNs == 0L) pendingShownTouchNs = sentAtNs
        if (sendDurationNs > maxSendNs) maxSendNs = sendDurationNs
        if (touchEventAtNs > 0L && sentAtNs >= touchEventAtNs) {
            touchEventToHid.add(sentAtNs - touchEventAtNs)
        }
    }

    /** Returns HID-send-to-next-frame-received latency, or -1 when no touch is pending. */
    fun onFrame(arrivedAtNs: Long): Long {
        val touch = pendingFrameTouchNs
        if (touch == 0L) return -1
        pendingFrameTouchNs = 0L
        val latencyNs = arrivedAtNs - touch
        if (latencyNs >= 0L) hidToFrameReceived.add(latencyNs)
        return latencyNs
    }

    /** Returns touch-to-surface-release latency for the first shown main-screen frame, or -1. */
    fun onFrameShown(nowNs: Long): Long {
        val touch = pendingShownTouchNs
        if (touch == 0L) return -1
        pendingShownTouchNs = 0L
        return nowNs - touch
    }

    fun onFrameDecoded(arrivedAtNs: Long, decodedAtNs: Long) {
        val latencyNs = decodedAtNs - arrivedAtNs
        if (arrivedAtNs > 0L && latencyNs >= 0L) frameReceivedToDecodeOutput.add(latencyNs)
    }

    fun onFrameSubmittedToSurface(decodedAtNs: Long, submittedAtNs: Long) {
        val latencyNs = submittedAtNs - decodedAtNs
        if (decodedAtNs > 0L && latencyNs >= 0L) decodeOutputToSurfaceSubmit.add(latencyNs)
    }

    /** Called by the main screen's five-second stats window, never by a per-frame formatter. */
    fun drainSummary(includeEmpty: Boolean = false): String? {
        val touch = touchEventToHid.drain()
        val hid = hidToFrameReceived.drain()
        val decode = frameReceivedToDecodeOutput.drain()
        val surface = decodeOutputToSurfaceSubmit.drain()
        if (touch == null && hid == null && decode == null && surface == null && !includeEmpty) return null
        return "touchEvent2Hid ${summary(touch)} " +
            "hid2FrameReceived ${summary(hid)} " +
            "frameReceived2DecodeOutput ${summary(decode)} " +
            "decodeOutput2SurfaceSubmit ${summary(surface)}"
    }

    internal fun resetForTest() {
        pendingFrameTouchNs = 0L
        pendingShownTouchNs = 0L
        maxSendNs = 0L
        touchEventToHid.reset()
        hidToFrameReceived.reset()
        frameReceivedToDecodeOutput.reset()
        decodeOutputToSurfaceSubmit.reset()
    }

    private fun summary(samples: LongArray?): String {
        if (samples == null) return "p50=-1ms p90=-1ms max=-1ms n=0"
        samples.sort()
        fun percentile(p: Int): Long {
            val index = ((samples.size * p + 99) / 100 - 1).coerceAtLeast(0)
            return samples[index]
        }
        fun millis(ns: Long): String = String.format(Locale.US, "%.1f", ns / 1_000_000.0)
        return "p50=${millis(percentile(50))}ms p90=${millis(percentile(90))}ms " +
            "max=${millis(samples.last())}ms n=${samples.size}"
    }

    /** Bounded samples per five-second window; all per-frame storage stays primitive and preallocated. */
    private class LatencySamples {
        private val values = LongArray(SAMPLE_CAPACITY)
        private var count = 0
        private var writeIndex = 0

        @Synchronized fun add(valueNs: Long) {
            if (valueNs < 0L) return
            values[writeIndex] = valueNs
            writeIndex = (writeIndex + 1) % values.size
            if (count < values.size) count++
        }

        @Synchronized fun drain(): LongArray? {
            if (count == 0) return null
            val copy = LongArray(count)
            val oldest = if (count == values.size) writeIndex else 0
            for (index in copy.indices) copy[index] = values[(oldest + index) % values.size]
            count = 0
            writeIndex = 0
            return copy
        }

        @Synchronized fun reset() {
            count = 0
            writeIndex = 0
        }
    }

    private const val SAMPLE_CAPACITY = 1024
}

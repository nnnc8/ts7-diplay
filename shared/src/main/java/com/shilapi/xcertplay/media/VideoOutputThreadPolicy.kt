package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.network.Ts7HeadUnit

/** Uses a dedicated output thread for the TS7 and for existing vendor low-latency configurations. */
fun shouldUseDedicatedVideoOutputThread(vendorLowLatencyDecoder: Boolean): Boolean =
    shouldUseDedicatedVideoOutputThread(vendorLowLatencyDecoder, Ts7HeadUnit.detected)

internal fun shouldUseDedicatedVideoOutputThread(
    vendorLowLatencyDecoder: Boolean,
    ts7: Boolean,
): Boolean = vendorLowLatencyDecoder || ts7

/** Raises the TS7 decode worker priority without changing other head units. */
fun shouldElevateVideoDecoderThreadPriority(): Boolean =
    shouldElevateVideoDecoderThreadPriority(Ts7HeadUnit.detected)

internal fun shouldElevateVideoDecoderThreadPriority(ts7: Boolean): Boolean = ts7

/** Skips an older output only when a newer decoded buffer is already waiting. */
fun shouldSkipOlderVideoOutput(
    hasNewerReady: Boolean,
    pacingDelayNanos: Long,
    framesAtOnce: Boolean,
): Boolean = shouldSkipOlderVideoOutput(
    hasNewerReady,
    pacingDelayNanos,
    framesAtOnce,
    Ts7HeadUnit.detected,
)

internal fun shouldSkipOlderVideoOutput(
    hasNewerReady: Boolean,
    pacingDelayNanos: Long,
    framesAtOnce: Boolean,
    ts7: Boolean,
): Boolean = ts7 && hasNewerReady && pacingDelayNanos == 0L && !framesAtOnce

/** Tracks decoded frames that are still in flight on the dedicated output thread. */
internal class VideoOutputThreadWatchdog(
    private val timeoutNanos: Long,
) {
    var inFlight: Int = 0
        private set
    private var lastOutputNanos = 0L
    private var timeoutArmed = false

    fun inputQueued(nowNanos: Long) {
        if (inFlight == 0) {
            lastOutputNanos = nowNanos
            timeoutArmed = true
        }
        inFlight++
    }

    fun outputDequeued(nowNanos: Long) {
        if (inFlight > 0) inFlight--
        if (inFlight > 0) {
            lastOutputNanos = nowNanos
            timeoutArmed = true
        } else {
            timeoutArmed = false
        }
    }

    fun fallbackReason(nowNanos: Long): String? =
        if (inFlight > 0 && timeoutArmed && nowNanos - lastOutputNanos >= timeoutNanos) {
            "no_output_2s"
        } else {
            null
        }

    fun reset() {
        inFlight = 0
        lastOutputNanos = 0L
        timeoutArmed = false
    }
}

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

/** Falls back on an input stall only when the TS7 output worker has pending new input. */
internal fun shouldFallbackOnInputStall(
    ts7: Boolean,
    outputThreadActive: Boolean,
    inputQueuedSinceLastOutput: Int,
): Boolean = ts7 && outputThreadActive && inputQueuedSinceLastOutput > 0

/** Keeps the exception type in diagnostics while bounding the exported reason length. */
internal fun outputThreadExceptionReason(exceptionClassName: String): String {
    val typeName = exceptionClassName.substringAfterLast('.').ifBlank { "Exception" }
    return ("exception:" + typeName).take(MAX_OUTPUT_THREAD_EXCEPTION_REASON_LENGTH)
}

internal const val MAX_OUTPUT_THREAD_EXCEPTION_REASON_LENGTH = 120

// With the H.264 end marker off, the decoder can retain one or two frames. At 30 fps,
// a real two-second output stall receives far more than three frames.
private const val MIN_INPUTS_WITHOUT_OUTPUT_FOR_FALLBACK = 3

/** Tracks decoded frames that are still in flight on the dedicated output thread. */
internal class VideoOutputThreadWatchdog(
    private val timeoutNanos: Long,
) {
    var inFlight: Int = 0
        private set
    var inputQueuedSinceLastOutput: Int = 0
        private set
    private var lastOutputNanos = 0L
    private var timeoutArmed = false

    fun inputQueued(nowNanos: Long) {
        if (inFlight == 0) {
            lastOutputNanos = nowNanos
            timeoutArmed = true
        }
        inFlight++
        inputQueuedSinceLastOutput++
    }

    fun outputDequeued(nowNanos: Long) {
        if (inFlight > 0) inFlight--
        inputQueuedSinceLastOutput = 0
        if (inFlight > 0) {
            lastOutputNanos = nowNanos
            timeoutArmed = true
        } else {
            timeoutArmed = false
        }
    }

    fun fallbackReason(nowNanos: Long): String? =
        if (
            inFlight > 0 &&
            inputQueuedSinceLastOutput >= MIN_INPUTS_WITHOUT_OUTPUT_FOR_FALLBACK &&
            timeoutArmed &&
            nowNanos - lastOutputNanos >= timeoutNanos
        ) {
            "no_output_2s"
        } else {
            null
        }

    fun reset() {
        inFlight = 0
        inputQueuedSinceLastOutput = 0
        lastOutputNanos = 0L
        timeoutArmed = false
    }
}

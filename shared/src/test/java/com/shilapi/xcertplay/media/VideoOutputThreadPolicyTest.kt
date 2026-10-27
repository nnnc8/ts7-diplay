package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoOutputThreadPolicyTest {
    @Test fun dedicatedOutputThreadIsEnabledForTs7AndExistingVendorMode() {
        assertFalse(shouldUseDedicatedVideoOutputThread(vendorLowLatencyDecoder = false, ts7 = false))
        assertTrue(shouldUseDedicatedVideoOutputThread(vendorLowLatencyDecoder = false, ts7 = true))
        assertTrue(shouldUseDedicatedVideoOutputThread(vendorLowLatencyDecoder = true, ts7 = false))
        assertFalse(shouldElevateVideoDecoderThreadPriority(ts7 = false))
        assertTrue(shouldElevateVideoDecoderThreadPriority(ts7 = true))
    }

    @Test fun onlyOlderUnpacedTs7OutputIsSkipped() {
        assertTrue(shouldSkipOlderVideoOutput(
            hasNewerReady = true,
            pacingDelayNanos = 0L,
            framesAtOnce = false,
            ts7 = true,
        ))
        assertFalse(shouldSkipOlderVideoOutput(
            hasNewerReady = false,
            pacingDelayNanos = 0L,
            framesAtOnce = false,
            ts7 = true,
        ))
        assertFalse(shouldSkipOlderVideoOutput(
            hasNewerReady = true,
            pacingDelayNanos = 1L,
            framesAtOnce = false,
            ts7 = true,
        ))
        assertFalse(shouldSkipOlderVideoOutput(
            hasNewerReady = true,
            pacingDelayNanos = 0L,
            framesAtOnce = true,
            ts7 = true,
        ))
        assertFalse(shouldSkipOlderVideoOutput(
            hasNewerReady = true,
            pacingDelayNanos = 0L,
            framesAtOnce = false,
            ts7 = false,
        ))
    }

    @Test fun watchdogIgnoresOneOrTwoFramesAfterLongPause() {
        for (queuedFrames in 1..2) {
            val watchdog = VideoOutputThreadWatchdog(timeoutNanos = 2_000_000_000L)
            repeat(queuedFrames) { index -> watchdog.inputQueued(nowNanos = index.toLong()) }
            assertEquals(queuedFrames, watchdog.inputQueuedSinceLastOutput)
            assertNull(watchdog.fallbackReason(nowNanos = 10_000_000_000L))
        }
    }

    @Test fun watchdogFallsBackAfterThreeInputsAndTwoSecondsWithoutOutput() {
        val watchdog = VideoOutputThreadWatchdog(timeoutNanos = 2_000_000_000L)
        repeat(2) { watchdog.inputQueued(nowNanos = it.toLong()) }
        assertNull(watchdog.fallbackReason(nowNanos = 2_000_000_000L))

        watchdog.inputQueued(nowNanos = 1_000_000_000L)
        assertNull(watchdog.fallbackReason(nowNanos = 1_999_999_999L))
        assertEquals("no_output_2s", watchdog.fallbackReason(nowNanos = 2_000_000_000L))
    }

    @Test fun outputAndResetClearInputCountSinceLastOutput() {
        val watchdog = VideoOutputThreadWatchdog(timeoutNanos = 2_000_000_000L)
        repeat(3) { watchdog.inputQueued(nowNanos = it.toLong()) }
        assertEquals(3, watchdog.inputQueuedSinceLastOutput)

        watchdog.outputDequeued(nowNanos = 1_000_000_000L)
        assertEquals(0, watchdog.inputQueuedSinceLastOutput)
        assertEquals(2, watchdog.inFlight)
        assertNull(watchdog.fallbackReason(nowNanos = 4_000_000_000L))

        watchdog.inputQueued(nowNanos = 1_500_000_000L)
        watchdog.inputQueued(nowNanos = 1_600_000_000L)
        assertNull(watchdog.fallbackReason(nowNanos = 5_000_000_000L))

        watchdog.reset()
        assertEquals(0, watchdog.inputQueuedSinceLastOutput)
        assertEquals(0, watchdog.inFlight)
    }

    @Test fun inputStallFallbackRequiresTs7ActiveOutputThreadAndNewInput() {
        assertTrue(shouldFallbackOnInputStall(ts7 = true, outputThreadActive = true, inputQueuedSinceLastOutput = 1))
        assertFalse(shouldFallbackOnInputStall(ts7 = false, outputThreadActive = true, inputQueuedSinceLastOutput = 1))
        assertFalse(shouldFallbackOnInputStall(ts7 = true, outputThreadActive = false, inputQueuedSinceLastOutput = 1))
        assertFalse(shouldFallbackOnInputStall(ts7 = true, outputThreadActive = true, inputQueuedSinceLastOutput = 0))
    }

    @Test fun exceptionReasonIncludesClassAndStaysBounded() {
        assertEquals("exception:IllegalStateException", outputThreadExceptionReason("java.lang.IllegalStateException"))
        val reason = outputThreadExceptionReason("x".repeat(MAX_OUTPUT_THREAD_EXCEPTION_REASON_LENGTH))
        assertTrue(reason.startsWith("exception:"))
        assertTrue(reason.length <= MAX_OUTPUT_THREAD_EXCEPTION_REASON_LENGTH)
    }
}

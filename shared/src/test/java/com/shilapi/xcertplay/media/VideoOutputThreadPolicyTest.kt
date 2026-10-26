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

    @Test fun watchdogRequiresPendingInputAndResetsItsTimeoutAfterOutput() {
        val watchdog = VideoOutputThreadWatchdog(timeoutNanos = 2_000_000_000L)
        assertNull(watchdog.fallbackReason(nowNanos = 2_000_000_000L))

        watchdog.inputQueued(nowNanos = 0L)
        assertNull(watchdog.fallbackReason(nowNanos = 1_999_999_999L))
        assertEquals("no_output_2s", watchdog.fallbackReason(nowNanos = 2_000_000_000L))
        watchdog.reset()

        watchdog.inputQueued(nowNanos = 3_000_000_000L)
        watchdog.inputQueued(nowNanos = 3_000_000_001L)
        watchdog.outputDequeued(nowNanos = 4_000_000_000L)
        assertEquals(1, watchdog.inFlight)
        assertNull(watchdog.fallbackReason(nowNanos = 5_999_999_999L))
        assertEquals("no_output_2s", watchdog.fallbackReason(nowNanos = 6_000_000_000L))

        watchdog.reset()
        watchdog.inputQueued(nowNanos = 7_000_000_000L)
        watchdog.outputDequeued(nowNanos = 7_000_000_001L)
        assertEquals(0, watchdog.inFlight)
        assertNull(watchdog.fallbackReason(nowNanos = 10_000_000_000L))

        watchdog.reset()
        assertEquals(0, watchdog.inFlight)
    }
}

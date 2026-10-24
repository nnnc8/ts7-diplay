package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TouchLatencyProbeTest {
    @Before fun resetProbe() = TouchLatencyProbe.resetForTest()

    @Test fun touchToReceivedAndTouchToShownKeepIndependentPairings() {
        TouchLatencyProbe.onTouchSent(sentAtNs = 100L, sendDurationNs = 5L)

        assertEquals(40L, TouchLatencyProbe.onFrame(140L))
        assertEquals(-1L, TouchLatencyProbe.onFrame(150L))
        assertEquals(90L, TouchLatencyProbe.onFrameShown(190L))
        assertEquals(-1L, TouchLatencyProbe.onFrameShown(200L))
    }

    @Test fun shownPairCanBeConsumedBeforeTheReceivedPair() {
        TouchLatencyProbe.onTouchSent(sentAtNs = 300L, sendDurationNs = 7L)

        assertEquals(50L, TouchLatencyProbe.onFrameShown(350L))
        assertEquals(80L, TouchLatencyProbe.onFrame(380L))
    }
}

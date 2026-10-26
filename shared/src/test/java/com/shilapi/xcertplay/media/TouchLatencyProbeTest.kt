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

    @Test fun fourPipelineSegmentsReportP50P90AndMaxFromThreeSamples() {
        val start = 1_000_000_000L
        for (sample in 1L..3L) {
            val touchAt = start + sample * 1_000_000_000L
            val sentAt = touchAt + sample * 10_000_000L
            val receivedAt = sentAt + (sample + 3L) * 10_000_000L
            val decodedAt = receivedAt + (sample + 5L) * 10_000_000L
            val surfaceAt = decodedAt + sample * 10_000_000L

            TouchLatencyProbe.onTouchSent(touchAt, sentAt, sentAt - touchAt)
            assertEquals(receivedAt - sentAt, TouchLatencyProbe.onFrame(receivedAt))
            TouchLatencyProbe.onFrameDecoded(receivedAt, decodedAt)
            TouchLatencyProbe.onFrameSubmittedToSurface(decodedAt, surfaceAt)
        }

        val summary = checkNotNull(TouchLatencyProbe.drainSummary())
        assertEquals(
            "touchEvent2Hid p50=20.0ms p90=30.0ms max=30.0ms n=3 " +
                "hid2FrameReceived p50=50.0ms p90=60.0ms max=60.0ms n=3 " +
                "frameReceived2DecodeOutput p50=70.0ms p90=80.0ms max=80.0ms n=3 " +
                "decodeOutput2SurfaceSubmit p50=20.0ms p90=30.0ms max=30.0ms n=3",
            summary,
        )
        assertEquals(null, TouchLatencyProbe.drainSummary())
    }
}

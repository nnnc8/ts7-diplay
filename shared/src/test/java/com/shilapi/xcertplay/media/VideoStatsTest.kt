package com.shilapi.xcertplay.media

import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class VideoStatsTest {
    @Before fun resetProbe() = TouchLatencyProbe.resetForTest()

    @Test fun mainVideoStatsAppendTouchToShownAfterTheExistingFields() {
        var now = 1_000_000_000L
        val stats = VideoStats(nanoTime = { now })
        TouchLatencyProbe.onTouchSent(
            touchEventAtNs = now - 10_000_000L,
            sentAtNs = now,
            sendDurationNs = 2_000_000L,
        )

        now += 80_000_000L
        val receivedAt = now
        stats.onReceived(500, receivedAt)
        now += 25_000_000L
        val decodedAt = now
        stats.onFrameDecoded(receivedAt, decodedAt)
        now += 5_000_000L
        stats.onFrameSubmittedToSurface(decodedAt, now)
        stats.onRendered()
        now += 5_000_000_000L
        val line = checkNotNull(stats.logIfDue())

        assertTrue(line, line.contains(
            "touch2frame avg=80ms max=80ms n=1 touchSendMax=2ms " +
                "touch2shown avg=110ms max=110ms n=1",
        ))
        assertTrue(line, line.contains("touchEvent2Hid p50=10.0ms p90=10.0ms max=10.0ms n=1"))
        assertTrue(line, line.contains("hid2FrameReceived p50=80.0ms p90=80.0ms max=80.0ms n=1"))
        assertTrue(line, line.contains("frameReceived2DecodeOutput p50=25.0ms p90=25.0ms max=25.0ms n=1"))
        assertTrue(line, line.contains("decodeOutput2SurfaceSubmit p50=5.0ms p90=5.0ms max=5.0ms n=1"))
    }
}

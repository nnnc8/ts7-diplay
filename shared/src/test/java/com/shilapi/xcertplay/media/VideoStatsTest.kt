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
        TouchLatencyProbe.onTouchSent(sentAtNs = now, sendDurationNs = 2_000_000L)

        now += 80_000_000L
        stats.onReceived(500)
        now += 30_000_000L
        stats.onRendered()
        now += 5_000_000_000L
        val line = checkNotNull(stats.logIfDue())

        assertTrue(line, line.contains(
            "touch2frame avg=80ms max=80ms n=1 touchSendMax=2ms " +
                "touch2shown avg=110ms max=110ms n=1",
        ))
    }
}

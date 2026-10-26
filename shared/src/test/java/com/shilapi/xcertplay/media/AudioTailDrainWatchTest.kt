package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioTailDrainWatchTest {
    @Test fun aStalledHeadNeedsAReadyTailAndOnePointFiveSecondsWithoutProgress() {
        val watch = AudioTailDrainWatch()
        val start = 1_000_000_000L

        assertNull(watch.exitReason(start, 10_000, 0, drainReady = false))
        assertNull(watch.exitReason(start + 1_499_999_999L, 10_000, 0, drainReady = false))
        assertEquals(
            AudioTailDrainExitReason.PLAYBACK_HEAD_STALLED,
            watch.exitReason(start + 1_500_000_000L, 10_000, 0, drainReady = true),
        )
    }

    @Test fun headProgressKeepsTheNaturalTailAlive() {
        val watch = AudioTailDrainWatch()
        val start = 2_000_000_000L

        assertNull(watch.exitReason(start, 10_000, 0, drainReady = true))
        assertNull(watch.exitReason(start + 1_000_000_000L, 10_000, 48_000, drainReady = true))
        assertNull(watch.exitReason(start + 2_000_000_000L, 10_000, 96_000, drainReady = true))
    }

    @Test fun hardLimitIsInitialQueueDurationPlusTwoSeconds() {
        val watch = AudioTailDrainWatch()
        val start = 3_000_000_000L

        assertNull(watch.exitReason(start, 500, 0, drainReady = false))
        assertNull(watch.exitReason(start + 2_499_999_999L, 500, 0, drainReady = false))
        assertEquals(
            AudioTailDrainExitReason.TIME_LIMIT,
            watch.exitReason(start + 2_500_000_000L, 500, 0, drainReady = false),
        )
    }
}

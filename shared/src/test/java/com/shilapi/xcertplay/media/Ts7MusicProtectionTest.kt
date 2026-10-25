package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class Ts7MusicProtectionTest {
    @Test fun onlyTs7MusicGetsTheAdaptiveBuffer() {
        for (type in listOf("telephony", "speechrecognition", "default", "compatibility", "alert")) {
            assertFalse(Ts7MusicBuffer.enabled(true, type))
        }
        assertFalse(Ts7MusicBuffer.enabled(false, "media"))
        assertTrue(Ts7MusicBuffer.enabled(true, "media"))
        val low = MediaAudioBuffer.plan(false, 48_000, 2, 4096, 500)
        assertEquals(low, MediaAudioBuffer.plan(false, 48_000, 2, 4096, 500, true))
    }

    @Test fun targetAndSharedQueueLimitFollowTheRebufferTarget() {
        val plan = MediaAudioBuffer.plan(true, 48_000, 2, 4096, 500, true)
        val buffer = Ts7MusicBuffer(48_000, 4, plan.trackBufferBytes, 500)
        assertEquals(2400 * 192, plan.trackBufferBytes)
        assertEquals(900 * 192, buffer.targetBytes)
        assertEquals(2100 * 192, buffer.totalLimitBytes)
        buffer.rebuffer(1)
        assertEquals(1200 * 192, buffer.targetBytes)
        assertEquals(2400 * 192, buffer.totalLimitBytes)
        repeat(10) { buffer.rebuffer(1) }
        assertEquals(2200 * 192, buffer.targetBytes)
        assertEquals(2400 * 192, buffer.totalLimitBytes)
        assertFalse(buffer.stable(Ts7MusicBuffer.DECAY_NS))
        assertTrue(buffer.stable(Ts7MusicBuffer.DECAY_NS + 1))
        assertEquals(2100 * 192, buffer.targetBytes)
        assertEquals(2400 * 192, buffer.totalLimitBytes)

        val small = Ts7MusicBuffer(48_000, 4, 33_600 * 4, 500)
        repeat(10) { small.rebuffer(1) }
        assertEquals(33_600 * 4 - 7_680, small.targetBytes)
        assertTrue(small.totalLimitBytes <= 33_600 * 4)
    }

    @Test fun completeLossAddsExactTimelineFramesAndLatePacketsAreDiscarded() {
        for (missing in listOf(18, 36)) {
            val gaps = Ts7MusicGapConcealer<String>(48_000)
            assertEquals(listOf("first"), gaps.accept(65530, 0, "first", 0))
            val sample = (missing + 1) * 1024
            val after = (65531 + missing) and 0xffff
            assertEquals(listOf("after"), gaps.accept(after, sample, "after", 40_000_000L))
            assertTrue(gaps.accept(65531, 1024, "late", 50_000_000L).isEmpty())
            assertEquals(1L, gaps.lateOrDuplicate)
            assertEquals(missing.toLong(), gaps.missingPackets)
            assertEquals((missing / 18).toLong(), gaps.lostBursts)
            val pts = sample.toLong() * 1_000_000 / 48_000
            assertEquals(missing * 1024, gaps.silenceBefore(pts))
            assertEquals(0, gaps.silenceBefore(pts))
        }
    }

    @Test fun smallReorderingDoesNotConcealPacketTwoInTheSequenceOneThreeTwo() {
        val gaps = Ts7MusicGapConcealer<String>(48_000)
        assertEquals(listOf("1"), gaps.accept(1, 0, "1", 0))
        assertTrue(gaps.accept(3, 2048, "3", 1).isEmpty())
        assertEquals(listOf("2", "3"), gaps.accept(2, 1024, "2", 2))
        assertEquals(0L, gaps.missingPackets)
        assertEquals(0, gaps.silenceBefore(2048L * 1_000_000 / 48_000))
    }

    @Test fun eightContinuousLatePacketsSynchronizeANewRtpSequence() {
        val gaps = Ts7MusicGapConcealer<String>(48_000)
        gaps.accept(1000, 100 * 1024, "old", 0)
        for (sequence in 0..6) {
            assertTrue(gaps.accept(sequence, sequence * 1024, "new-$sequence", sequence.toLong()).isEmpty())
        }
        assertEquals((0..7).map { "new-$it" }, gaps.accept(7, 7 * 1024, "new-7", 7))
        assertEquals(0L, gaps.missingPackets)
        assertEquals(0L, gaps.lateOrDuplicate)
        assertEquals(0, gaps.silenceBefore(0))
    }

    @Test fun timestampWrapAndNonAacJumpsDoNotAllocateGapFrames() {
        val gaps = Ts7MusicGapConcealer<String>(48_000)
        gaps.accept(1, -1024, "first", 0)
        gaps.accept(20, 18 * 1024, "wrapped", 40_000_000L)
        assertEquals(18 * 1024, gaps.silenceBefore(18 * 1024L * 1_000_000 / 48_000))
        gaps.accept(200, 199 * 1024, "jump", 50_000_000L)
        assertEquals(0, gaps.silenceBefore(199 * 1024L * 1_000_000 / 48_000))
        gaps.accept(220, 0, "restart", 60_000_000L)
        assertEquals(0, gaps.silenceBefore(0))
    }

    @Test fun expiredReorderWindowCommitsOneGapOnce() {
        val gaps = Ts7MusicGapConcealer<String>(48_000)
        gaps.accept(1, 0, "1", 0)
        assertTrue(gaps.accept(3, 2048, "3", 1).isEmpty())
        assertEquals(listOf("3"), gaps.flushExpired(Ts7MusicGapConcealer.REORDER_WAIT_NS + 2))
        assertEquals(1L, gaps.missingPackets)
        assertEquals(1024, gaps.silenceBefore(2048L * 1_000_000 / 48_000))
        assertTrue(gaps.flushExpired(Ts7MusicGapConcealer.REORDER_WAIT_NS * 2).isEmpty())
        assertEquals(1L, gaps.missingPackets)
    }

    @Test fun playbackLatencyDropsOnlyAfterTenSecondsAboveTheGraceThenContinueUntilTheLimit() {
        val policy = Ts7MusicLatencyPolicy()
        assertTrue(policy.shouldDropBeforePlayback(2101, 900))
        assertFalse(policy.shouldDropBeforePlayback(2100, 900))
        assertFalse(policy.shouldDropDuringPlayback(2901, 900, 1_000_000_000L))
        assertFalse(policy.shouldDropDuringPlayback(2901, 900, 10_999_999_999L))
        assertFalse(policy.shouldDropDuringPlayback(2900, 900, 11_000_000_000L))
        assertFalse(policy.shouldDropDuringPlayback(2901, 900, 15_000_000_000L))
        assertTrue(policy.shouldDropDuringPlayback(2901, 900, 25_000_000_000L))
        assertTrue(policy.shouldDropDuringPlayback(2150, 900, 25_010_000_000L))
        assertFalse(policy.shouldDropDuringPlayback(2100, 900, 25_020_000_000L))
        assertFalse(policy.shouldDropDuringPlayback(2901, 900, 26_000_000_000L))
    }

    @Test fun burstStatsReportSizeAndPeriodWithoutRetainingPackets() {
        val stats = AudioBurstStats()
        for (burst in 0..3) repeat(18) { stats.received(burst * 384_000_000L + it * 250_000L) }
        assertEquals("burstPacketsMax=18 burstPeriodMs=384", stats.snapshot())
    }

    @Test fun ts7NeverLearnsDuringRecoveryOrSpeedsUpAnUnderfilledQueue() {
        MediaClockTrim.learnedTrim = 0.015
        try {
            val trim = MediaClockTrim(48_000, 43_200, measuresTarget = true, ts7Music = true)
            trim.suspendLearning(0)
            for (second in 0..100) {
                trim.sample(if (second < 10) 0 else 100_000, second * 1_000_000_000L)
                trim.rateDue(second * 1_000_000_000L)
                assertTrue(trim.rate in 47_856..48_144)
                if (second < 5) assertEquals(48_000, trim.rate)
            }
            trim.sample(0, 101_000_000_000)
            trim.rateDue(101_000_000_000)
            assertTrue(trim.rate <= 48_000)
            assertEquals(0.015, MediaClockTrim.learnedTrim, 0.0)
        } finally { MediaClockTrim.learnedTrim = 0.0 }
    }
}

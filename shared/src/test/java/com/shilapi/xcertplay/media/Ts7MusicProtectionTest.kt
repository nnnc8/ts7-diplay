package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class Ts7MusicProtectionTest {
    @Test fun onlyTs7MusicGetsTheLongBuffer() {
        for (type in listOf("telephony", "speechrecognition", "default", "compatibility", "alert")) {
            assertFalse(Ts7MusicBuffer.enabled(true, type))
        }
        assertFalse(Ts7MusicBuffer.enabled(false, "media"))
        assertTrue(Ts7MusicBuffer.enabled(true, "media"))
        val low = MediaAudioBuffer.plan(false, 48_000, 2, 4096, 500)
        assertEquals(low, MediaAudioBuffer.plan(false, 48_000, 2, 4096, 500, true))
    }

    @Test fun rebufferRaisesTheTargetWithinActualCapacityAndDecaysSlowly() {
        val plan = MediaAudioBuffer.plan(true, 48_000, 2, 4096, 500, true)
        val buffer = Ts7MusicBuffer(48_000, 4, plan.trackBufferBytes, 500)
        assertEquals(900 * 192, buffer.targetBytes)
        buffer.rebuffer(1)
        assertEquals(1200 * 192, buffer.targetBytes)
        repeat(10) { buffer.rebuffer(1) }
        assertEquals(2200 * 192, buffer.targetBytes)
        assertFalse(buffer.stable(Ts7MusicBuffer.DECAY_NS))
        assertTrue(buffer.stable(Ts7MusicBuffer.DECAY_NS + 1))
        assertEquals(2100 * 192, buffer.targetBytes)
        val small = Ts7MusicBuffer(48_000, 4, 33600 * 4, 500)
        repeat(10) { small.rebuffer(1) }
        assertEquals(33600 * 4 - 4096, small.targetBytes)
    }

    @Test fun completeLostBurstsHaveExactBoundedSilenceAndLatePacketsAreDiscarded() {
        for (missing in listOf(18, 36)) {
            val gaps = Ts7MusicGapConcealer(48_000)
            assertTrue(gaps.accept(65530, 0))
            val sample = (missing + 1) * 1024
            assertTrue(gaps.accept((65531 + missing) and 0xffff, sample))
            assertFalse(gaps.accept(65531, 1024))
            assertEquals(1L, gaps.lateOrDuplicate)
            assertEquals(missing.toLong(), gaps.missingPackets)
            assertEquals((missing / 18).toLong(), gaps.lostBursts)
            val pts = sample.toLong() * 1_000_000 / 48_000
            assertEquals(missing * 1024, gaps.silenceBefore(pts))
            assertEquals(0, gaps.silenceBefore(pts))
        }
    }

    @Test fun timestampWrapWorksButRestartAndHugeGapsNeverAllocateSilence() {
        val gaps = Ts7MusicGapConcealer(48_000)
        gaps.accept(1, -1024)
        gaps.accept(20, 18 * 1024)
        assertEquals(18 * 1024, gaps.silenceBefore(18 * 1024L * 1_000_000 / 48_000))
        gaps.accept(200, 199 * 1024)
        assertEquals(0, gaps.silenceBefore(199 * 1024L * 1_000_000 / 48_000))
        gaps.accept(220, 0)
        assertEquals(0, gaps.silenceBefore(0))
    }

    @Test fun silenceKeepsTheTimelineForRepeatedLostBursts() {
        // Nine minutes at 48 kHz; lose 18 or 36 consecutive AAC frames every 15 seconds.
        var plain = 43_200L
        var concealed = plain
        var plainStarves = 0
        var concealedStarves = 0
        val gap = Ts7MusicGapConcealer(48_000)
        var frame = 0
        var lostUntil = 0
        while (frame < 25_000) {
            if (frame > 0 && frame % 700 == 0) lostUntil = frame + if (frame % 1400 == 0) 36 else 18
            if (frame >= lostUntil) {
                gap.accept(frame and 0xffff, frame * 1024)
                plain += 1024
                concealed += 1024 + gap.silenceBefore(frame * 1024L * 1_000_000 / 48_000)
            }
            plain -= 1024
            concealed -= 1024
            if (plain < 0) { plainStarves++; plain = 43_200 }
            if (concealed < 0) { concealedStarves++; concealed = 43_200 }
            frame++
        }
        assertTrue(plainStarves > 5)
        assertEquals(0, concealedStarves)
    }

    @Test fun burstsReportSizeAndPeriodWithoutRetainingPackets() {
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

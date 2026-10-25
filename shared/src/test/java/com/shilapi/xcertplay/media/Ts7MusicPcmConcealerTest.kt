package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class Ts7MusicPcmConcealerTest {
    @Test fun onePacketGapRepeatsThePreviousPcmWithoutChangingTimelineLength() {
        val concealer = Ts7MusicPcmConcealer(48_000, 1)
        val packet = pcm(1024, 1, 12_000)
        val first = concealer.process(packet, 0)
        val second = concealer.process(packet, 1024)
        val all = (first.chunks + second.chunks).map { it.pcm } + listOfNotNull(concealer.flushTail())

        assertEquals(1L, concealer.shortGapsConcealed)
        assertEquals(0L, concealer.longGapsConcealed)
        assertEquals(1024L, concealer.repeatFrames)
        assertEquals(1024, second.chunks[1].pcm.size / 2)
        assertFalse(second.chunks[1].silence)
        assertEquals(3 * 1024, all.sumOf { it.size / 2 })
        assertJoinedWithoutStep(all, 2)
    }

    @Test fun secondAndThirdRepeatedPacketsFadeAboutSixDecibelsPerPacket() {
        val concealer = Ts7MusicPcmConcealer(48_000, 1)
        val packet = pcm(1024, 1, 20_000)
        concealer.process(packet, 0)
        val result = concealer.process(packet, 3 * 1024)
        val gap = result.chunks[1].pcm
        assertEquals(3 * 1024, gap.size / 2)
        assertEquals(10_024, sample(gap, 1024 + 400))
        assertEquals(5_024, sample(gap, 2 * 1024 + 400))
        assertEquals(3_072L, result.repeatFrames)
    }

    @Test fun repeatedNegativePcmKeepsItsPolarity() {
        val concealer = Ts7MusicPcmConcealer(48_000, 1)
        val packet = pcm(1024, 1, -12_000)
        concealer.process(packet, 0)
        val result = concealer.process(packet, 1024)

        assertTrue(sample(result.chunks[1].pcm, 200) < 0)
        assertTrue(sample(result.chunks[1].pcm, 200) > Short.MIN_VALUE)
    }

    @Test fun longGapFadesEightMillisecondsAtBothEndsAndKeepsAllFrames() {
        val concealer = Ts7MusicPcmConcealer(48_000, 1)
        val packet = pcm(1024, 1, 20_000)
        val first = concealer.process(packet, 0)
        val result = concealer.process(packet, 4 * 1024)
        val previousTail = result.chunks[0].pcm
        val silence = result.chunks[1]
        val current = result.chunks[2].pcm

        assertEquals(1L, concealer.longGapsConcealed)
        assertEquals(4 * 1024, silence.pcm.size / 2)
        assertTrue(silence.silence)
        assertEquals(0, sample(previousTail, previousTail.size / 2 - 1))
        assertTrue(sample(current, 0) in 0..60)
        assertEquals(6 * 1024, first.chunks.sumOf { it.pcm.size / 2 } + result.chunks.sumOf { it.pcm.size / 2 } +
            concealer.flushTail()!!.size / 2)
    }

    @Test fun shortGapAtStreamStartFallsBackToSilence() {
        val concealer = Ts7MusicPcmConcealer(48_000, 1)
        val result = concealer.process(pcm(1024, 1, 10_000), 1024)

        assertEquals(1L, concealer.shortGapsConcealed)
        assertEquals(0L, concealer.repeatFrames)
        assertTrue(result.chunks.first().silence)
        assertTrue(result.chunks.first().pcm.all { it == 0.toByte() })
        assertEquals(2048, result.chunks.sumOf { it.pcm.size / 2 } + concealer.flushTail()!!.size / 2)
    }

    @Test fun resetTimelineForgetsThePreviousSongPcmButKeepsCounters() {
        val concealer = Ts7MusicPcmConcealer(48_000, 1)
        val packet = pcm(1024, 1, -10_000)
        concealer.process(packet, 0)
        concealer.process(packet, 1024)
        val concealedBeforeSkip = concealer.shortGapsConcealed

        concealer.resetTimeline()
        val afterSkip = concealer.process(packet, 1024)

        assertEquals(concealedBeforeSkip + 1, concealer.shortGapsConcealed)
        assertEquals(0L, afterSkip.repeatFrames)
        assertTrue(afterSkip.chunks.first().silence)
        assertTrue(afterSkip.chunks.first().pcm.all { it == 0.toByte() })
    }

    private fun assertJoinedWithoutStep(chunks: List<ByteArray>, channels: Int) {
        val samples = chunks.flatMap { chunk -> (0 until chunk.size / 2 step channels).map { sample(chunk, it) } }
        val maxStep = samples.zipWithNext().maxOf { (a, b) -> kotlin.math.abs(a - b) }
        assertTrue("max adjacent PCM step was $maxStep", maxStep <= 160)
    }

    private fun pcm(frames: Int, channels: Int, value: Int): ByteArray = ByteArray(frames * channels * 2).also { bytes ->
        repeat(frames * channels) { index ->
            bytes[index * 2] = value.toByte()
            bytes[index * 2 + 1] = (value shr 8).toByte()
        }
    }

    private fun sample(bytes: ByteArray, frame: Int): Int {
        val offset = frame * 2
        return ((bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)).toShort().toInt()
    }
}

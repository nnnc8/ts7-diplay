package com.shilapi.xcertplay.media

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class VoiceFilterTest {
    private val rate = 48_000

    private fun tone(hz: Double, channels: Int, seconds: Double = 0.5): ByteArray {
        val frames = (rate * seconds).toInt()
        return ByteArray(frames * channels * 2).also { out ->
            for (i in 0 until frames) {
                val s = (10_000 * sin(2 * PI * hz * i / rate)).toInt()
                for (c in 0 until channels) {
                    val at = (i * channels + c) * 2
                    out[at] = s.toByte(); out[at + 1] = (s shr 8).toByte()
                }
            }
        }
    }

    // RMS over the second half, after the filter has settled.
    private fun rms(pcm: ByteArray, channels: Int, channel: Int): Double {
        val frames = pcm.size / (2 * channels)
        var sum = 0.0
        for (i in frames / 2 until frames) {
            val at = (i * channels + channel) * 2
            val s = ((pcm[at + 1].toInt() shl 8) or (pcm[at].toInt() and 0xff)).toDouble()
            sum += s * s
        }
        return sqrt(sum / (frames - frames / 2))
    }

    private fun gain(hz: Double, channels: Int = 1, channel: Int = 0): Double {
        val pcm = tone(hz, channels)
        val before = rms(pcm, channels, channel)
        VoiceFilter(rate, channels).process(pcm, 0, pcm.size)
        return rms(pcm, channels, channel) / before
    }

    @Test fun deepBassIsCut() = assertTrue(gain(60.0) < 0.05)

    @Test fun theCutoffIsAboutThreeDecibelsDown() = assertEquals(0.707, gain(VoiceFilter.DEFAULT_CUTOFF_HZ), 0.05)

    @Test fun speechPassesUnchanged() {
        assertEquals(1.0, gain(1_000.0), 0.02)
        assertEquals(1.0, gain(3_000.0), 0.02)
    }

    @Test fun bothStereoChannelsAreFilteredIndependently() {
        assertTrue(gain(60.0, channels = 2, channel = 1) < 0.05)
        assertEquals(1.0, gain(1_000.0, channels = 2, channel = 1), 0.02)
    }

    @Test fun onlyTheGivenRangeIsChanged() {
        val pcm = tone(60.0, 1)
        val untouched = pcm.copyOfRange(0, 100)
        VoiceFilter(rate, 1).process(pcm, 100, pcm.size - 100)
        assertArrayEquals(untouched, pcm.copyOfRange(0, 100))
    }

    @Test fun randomizedPcmMatchesThePreviousListBasedImplementationBitForBit() {
        val random = Random(0xF117_EA7L)
        for (sampleRate in listOf(8_000, 16_000, 44_100, 48_000)) {
            for (channels in 1..2) {
                val frames = 3_000
                val frameBytes = 2 * channels
                val offset = 3 * frameBytes
                val actual = ByteArray(offset + frames * frameBytes + 5) { random.nextInt(256).toByte() }
                val expected = actual.copyOf()
                val optimized = VoiceFilter(sampleRate, channels, cutoffHz = 180.0)
                val previous = PreviousVoiceFilter(sampleRate, channels, cutoffHz = 180.0)
                var frame = 0
                while (frame < frames) {
                    val count = minOf(frames - frame, random.nextInt(96) + 1)
                    val byteOffset = offset + frame * frameBytes
                    val byteLength = count * frameBytes
                    optimized.process(actual, byteOffset, byteLength)
                    previous.process(expected, byteOffset, byteLength)
                    frame += count
                }
                assertArrayEquals("rate=$sampleRate channels=$channels", expected, actual)
            }
        }
    }

    /** Original stage-list implementation retained as a deterministic output oracle. */
    private class PreviousVoiceFilter(sampleRate: Int, private val channels: Int, cutoffHz: Double) {
        private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

        private val stages: List<Biquad> = listOf(0.5411961, 1.3065630).map { highPass(sampleRate, cutoffHz, it) }
        private val state = Array(stages.size * channels) { DoubleArray(4) }

        fun process(pcm: ByteArray, offset: Int, length: Int) {
            val frameBytes = 2 * channels
            for (frame in 0 until length / frameBytes) {
                for (channel in 0 until channels) {
                    val at = offset + frame * frameBytes + channel * 2
                    var x = ((pcm[at + 1].toInt() shl 8) or (pcm[at].toInt() and 0xff)).toDouble()
                    stages.forEachIndexed { index, stage ->
                        val h = state[index * channels + channel]
                        val y = stage.b0 * x + stage.b1 * h[0] + stage.b2 * h[1] - stage.a1 * h[2] - stage.a2 * h[3]
                        h[1] = h[0]; h[0] = x
                        h[3] = h[2]; h[2] = y
                        x = y
                    }
                    val sample = x.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    pcm[at] = sample.toByte()
                    pcm[at + 1] = (sample shr 8).toByte()
                }
            }
        }

        private fun highPass(rate: Int, cutoff: Double, q: Double): Biquad {
            val w = 2 * PI * cutoff / rate
            val alpha = sin(w) / (2 * q)
            val cosW = cos(w)
            val a0 = 1 + alpha
            return Biquad(
                b0 = (1 + cosW) / 2 / a0,
                b1 = -(1 + cosW) / a0,
                b2 = (1 + cosW) / 2 / a0,
                a1 = -2 * cosW / a0,
                a2 = (1 - alpha) / a0,
            )
        }
    }
}

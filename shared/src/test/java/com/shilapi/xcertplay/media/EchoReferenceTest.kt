package com.shilapi.xcertplay.media

import java.util.Random
import kotlin.math.abs
import kotlin.math.sign
import org.junit.Assert.*
import org.junit.Test

class EchoReferenceTest {
    private fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).also { out ->
        samples.forEachIndexed { i, s -> out[2 * i] = s.toByte(); out[2 * i + 1] = (s shr 8).toByte() }
    }

    @Test fun nothingIsReturnedBeforePlaybackStarts() {
        val reference = EchoReference(sampleRate = 1_000)
        reference.append(pcm(1, 2, 3, 4), 0, 8, 1, pendingFrames = null, nowNs = 0)
        val out = ShortArray(2) { 9 }
        assertFalse(reference.read(out, endNs = 10_000_000))
        assertArrayEquals(shortArrayOf(0, 0), out)
    }

    @Test fun readsTheSamplesThatWerePlayingAtTheRequestedTime() {
        // 1 kHz: one sample per millisecond. Ten samples written, four still queued at t=0,
        // so sample 6 is reaching the speaker at t=0 and sample 8 at t=2 ms.
        val reference = EchoReference(sampleRate = 1_000)
        reference.append(pcm(*IntArray(10) { it * 100 }), 0, 20, 1, pendingFrames = 4, nowNs = 0)
        val out = ShortArray(3)
        assertTrue(reference.read(out, endNs = 2_000_000))
        assertArrayEquals(shortArrayOf(500, 600, 700), out)
    }

    @Test fun samplesPastWhatWasWrittenOrAlreadyOverwrittenAreSilence() {
        val reference = EchoReference(sampleRate = 1_000, capacityMillis = 4)
        reference.append(pcm(*IntArray(6) { (it + 1) * 10 }), 0, 12, 1, pendingFrames = 0, nowNs = 0)
        val out = ShortArray(8)
        assertTrue(reference.read(out, endNs = 2_000_000))
        // Samples 0-5 written, only 2-5 kept; the window covers samples 0-7.
        assertArrayEquals(shortArrayOf(0, 0, 30, 40, 50, 60, 0, 0), out)
    }

    @Test fun stereoIsDownmixedToMono() {
        val reference = EchoReference(sampleRate = 1_000)
        reference.append(pcm(100, 300, -200, -400), 0, 8, 2, pendingFrames = 0, nowNs = 0)
        val out = ShortArray(2)
        assertTrue(reference.read(out, endNs = 0))
        assertArrayEquals(shortArrayOf(200, -300), out)
    }

    @Test fun resetForgetsThePlayedAudio() {
        val reference = EchoReference(sampleRate = 1_000)
        reference.append(pcm(1, 2), 0, 4, 1, pendingFrames = 0, nowNs = 0)
        reference.reset()
        assertFalse(reference.read(ShortArray(2), endNs = 0))
    }

    // 1 kHz, sample n holds value n, so a window's first value is the sample position it started at.
    private fun ramp(samples: Int = 1_000) = EchoReference(sampleRate = 1_000).also {
        it.append(pcm(*IntArray(samples) { n -> n }), 0, samples * 2, 1, pendingFrames = samples.toLong(), nowNs = 0)
    }

    /** Moves the speaker anchor without writing audio: [played] samples have reached the speaker at [nowMs]. */
    private fun EchoReference.anchor(played: Long, nowMs: Long, written: Long = 1_000) =
        append(ByteArray(0), 0, 0, 1, pendingFrames = written - played, nowNs = nowMs * 1_000_000)

    @Test fun jitteryClockEstimatesStillGiveContiguousWindows() {
        val reference = ramp()
        val out = ShortArray(10)
        val jitter = intArrayOf(0, 3, -4, 2, -1, 4, -3, 1, -2, 0, 3, -4, 2, -1, 4, -3, 1, -2, 0, 3)
        val starts = jitter.mapIndexed { frame, error ->
            val endMs = 10L * (frame + 1)
            // The true speaker position is endMs; the anchor reports it off by up to 4 ms.
            reference.anchor(played = endMs + error, nowMs = endMs)
            assertTrue(reference.read(out, endNs = endMs * 1_000_000))
            for (i in 1 until out.size) assertEquals(out[0] + i, out[i].toInt())
            out[0].toInt()
        }
        assertEquals(0, starts[0])
        for (frame in 1 until starts.size) assertEquals("frame $frame", starts[frame - 1] + 10, starts[frame])
    }

    @Test fun aSteadyOffsetIsCorrectedOnceTheEstimateSettles() {
        val reference = ramp()
        val out = ShortArray(10)
        // The first estimate happens to be 4 ms early; every later one is exact.
        val starts = (0 until 12).map { frame ->
            val endMs = 100L + 10 * frame
            reference.anchor(played = endMs + if (frame == 0) -4 else 0, nowMs = endMs)
            reference.read(out, endNs = endMs * 1_000_000)
            out[0].toInt()
        }
        assertEquals(86, starts[0])
        assertEquals(176, starts[9])
        assertEquals(190, starts[10])
        assertEquals(200, starts[11])
    }

    @Test fun aLargeSlipResynchronisesToTheEstimate() {
        val reference = ramp()
        val out = ShortArray(10)
        val starts = (0 until 30).map { frame ->
            val endMs = 10L * (frame + 1)
            // From frame 15 on, playback is 50 ms behind (an underrun the cursor did not see).
            reference.anchor(played = endMs - if (frame >= 15) 50 else 0, nowMs = endMs)
            reference.read(out, endNs = endMs * 1_000_000)
            out[0].toInt()
        }
        assertEquals(190, starts[19])
        assertEquals(200 - 50, starts[20])
        assertEquals(290 - 50, starts[29])
    }

    @Test fun playbackStoppingRestartsTheCursorFromTheNextEstimate() {
        val reference = ramp()
        val out = ShortArray(10)
        reference.anchor(played = 10, nowMs = 10)
        reference.read(out, endNs = 10_000_000)
        assertEquals(0, out[0].toInt())
        reference.append(ByteArray(0), 0, 0, 1, pendingFrames = null, nowNs = 20_000_000)
        assertFalse(reference.read(out, endNs = 20_000_000))
        reference.anchor(played = 500, nowMs = 600)
        assertTrue(reference.read(out, endNs = 600_000_000))
        assertEquals(490, out[0].toInt())
    }

    @Test fun randomizedRingReadsMatchThePreviousLongModuloImplementation() {
        val random = Random(0x7A55_2026L)
        val optimized = EchoReference(sampleRate = 1_000, capacityMillis = 23)
        val previous = PreviousEchoReference(sampleRate = 1_000, capacityMillis = 23)
        var written = 0L
        var nowNs = 0L

        repeat(600) {
            if (random.nextInt(37) == 0) {
                optimized.reset()
                previous.reset()
                written = 0
            }

            val channels = if (random.nextBoolean()) 1 else 2
            val frames = random.nextInt(31)
            val offset = random.nextInt(5)
            val length = frames * channels * 2
            val pcm = ByteArray(offset + length + random.nextInt(5)) { random.nextInt(256).toByte() }
            val pendingFrames = if (random.nextInt(7) == 0) null else random.nextInt((written + 16).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() + 1).toLong()
            nowNs += random.nextInt(20_000_001).toLong()
            optimized.append(pcm, offset, length, channels, pendingFrames, nowNs)
            previous.append(pcm, offset, length, channels, pendingFrames, nowNs)
            written += frames

            if (random.nextBoolean()) {
                val outSize = random.nextInt(30)
                val optimizedOut = ShortArray(outSize)
                val previousOut = ShortArray(outSize)
                val endNs = nowNs + random.nextInt(61_000_001).toLong() - 20_000_000L
                assertEquals(previous.read(previousOut, endNs), optimized.read(optimizedOut, endNs))
                assertArrayEquals(previousOut, optimizedOut)
            }
        }
    }

    /** Original Long-modulo implementation retained as a deterministic output oracle. */
    private class PreviousEchoReference(val sampleRate: Int, capacityMillis: Int = 2_000) {
        private val ring = ShortArray(sampleRate * capacityMillis / 1000)
        private var written = 0L
        private var anchorSample = NOT_PLAYING
        private var anchorNs = 0L
        private var cursor = NOT_PLAYING
        private var settled = false
        private val errors = LongArray(ERROR_WINDOW)
        private var errorAt = 0
        private var errorCount = 0

        @Synchronized
        fun append(pcm: ByteArray, offset: Int, length: Int, channels: Int, pendingFrames: Long?, nowNs: Long) {
            val frameBytes = 2 * channels
            for (frame in 0 until length / frameBytes) {
                val base = offset + frame * frameBytes
                var sum = 0
                for (channel in 0 until channels) {
                    val at = base + channel * 2
                    sum += (pcm[at + 1].toInt() shl 8) or (pcm[at].toInt() and 0xff)
                }
                ring[(written % ring.size).toInt()] = (sum / channels).toShort()
                written++
            }
            if (pendingFrames == null) {
                anchorSample = NOT_PLAYING
            } else {
                anchorSample = written - pendingFrames.coerceIn(0L, written)
                anchorNs = nowNs
            }
        }

        @Synchronized
        fun reset() {
            written = 0
            anchorSample = NOT_PLAYING
            cursor = NOT_PLAYING
        }

        @Synchronized
        fun read(out: ShortArray, endNs: Long): Boolean {
            if (anchorSample == NOT_PLAYING) {
                cursor = NOT_PLAYING
                out.fill(0)
                return false
            }
            val estimate = anchorSample + Math.floorDiv((endNs - anchorNs) * sampleRate, 1_000_000_000L) - out.size
            val start = steer(estimate)
            cursor = start + out.size
            val oldest = maxOf(0L, written - ring.size)
            var heard = false
            for (i in out.indices) {
                val sample = start + i
                out[i] = if (sample in oldest until written) {
                    heard = true
                    ring[(sample % ring.size).toInt()]
                } else {
                    0
                }
            }
            return heard
        }

        private fun steer(estimate: Long): Long {
            if (cursor == NOT_PLAYING) {
                cursor = estimate
                settled = false
                errorCount = 0
                return cursor
            }
            errors[errorAt] = estimate - cursor
            errorAt = (errorAt + 1) % errors.size
            if (errorCount < errors.size) errorCount++
            if (errorCount == errors.size) {
                val mean = errors.sum() / errors.size
                when {
                    abs(mean) > sampleRate * RESYNC_MILLIS / 1000L -> {
                        cursor = estimate
                        settled = false
                        errorCount = 0
                    }
                    !settled -> {
                        cursor += mean
                        settled = true
                        errorCount = 0
                    }
                    abs(mean) > sampleRate * SLEW_MILLIS / 1000L -> cursor += mean.sign
                }
            }
            return cursor
        }

        private companion object {
            const val NOT_PLAYING = Long.MIN_VALUE
            const val ERROR_WINDOW = 10
            const val SLEW_MILLIS = 5
            const val RESYNC_MILLIS = 20
        }
    }
}

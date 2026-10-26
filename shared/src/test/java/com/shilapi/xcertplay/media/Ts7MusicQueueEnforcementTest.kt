package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import java.util.concurrent.BlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [Ts7MusicQueueEnforcementTest.TestTrack::class])
class Ts7MusicQueueEnforcementTest {
    @Test fun startupDropsOldestCompressedPacketsUntilTotalQueueFitsL() {
        val fixture = createFixture(packetCount = 100, playbackStarted = false)
        try {
            val dropped = fixture.get("packetsDroppedTotal") as java.util.concurrent.atomic.AtomicInteger
            repeat(10) { fixture.invoke("enforceLatencyLimit") }
            fixture.invoke("enforceLatencyLimit") // Refresh the diagnostic snapshot after the last drop.
            assertEquals(98, fixture.queue.size)
            assertEquals(2, (fixture.get("droppedForLatency") as Long).toInt())
            assertEquals(2, dropped.get())
            assertTrue((fixture.get("totalQueuedMs") as Long) <= 2100L)
        } finally {
            fixture.track.release()
        }
    }

    @Test fun rebufferUsesExpandedLimitAndNeverQueuesMoreThanTwentyFourHundredMilliseconds() {
        val fixture = createFixture(packetCount = 140, playbackStarted = false)
        try {
            val buffer = fixture.get("adaptiveBuffer") as Ts7MusicBuffer
            repeat(5) { buffer.rebuffer(System.nanoTime()) }
            assertEquals(2200, buffer.targetMillis)
            assertEquals(2400 * 192, buffer.totalLimitBytes)
            assertTrue(buffer.totalLimitBytes <= 2400 * 192)

            repeat(40) {
                if (fixture.queue.isNotEmpty()) fixture.invoke("enforceLatencyLimit")
            }
            fixture.invoke("enforceLatencyLimit") // Refresh the diagnostic snapshot after the last drop.
            assertEquals(112, fixture.queue.size)
            assertEquals(28L, fixture.get("droppedForLatency"))
            assertTrue((fixture.get("totalQueuedMs") as Long) <= 2400L)
        } finally {
            fixture.track.release()
        }
    }

    @Test fun normalPlaybackWithinLatencyGraceDoesNotDropPacketsAfterTenSeconds() {
        val fixture = createFixture(packetCount = 130, playbackStarted = true)
        try {
            val policy = fixture.get("latencyPolicy") as Ts7MusicLatencyPolicy
            assertFalse(policy.shouldDropDuringPlayback(2800, 900, 1_000_000_000L))
            assertFalse(policy.shouldDropDuringPlayback(2800, 900, 11_000_000_000L))

            fixture.invoke("enforceLatencyLimit")
            assertEquals(130, fixture.queue.size)
            assertEquals(0L, fixture.get("droppedForLatency"))
            assertTrue((fixture.get("totalQueuedMs") as Long) in 2101L..2900L)
        } finally {
            fixture.track.release()
        }
    }

    @Test fun playbackDropsOneOldPacketAtATimeAfterTenSecondsAboveGrace() {
        val fixture = createFixture(packetCount = 150, playbackStarted = true)
        try {
            val policy = fixture.get("latencyPolicy") as Ts7MusicLatencyPolicy
            val nowNs = System.nanoTime()
            assertTrue(!policy.shouldDropDuringPlayback(3200, 900,
                nowNs - Ts7MusicBuffer.PLAYBACK_DROP_DELAY_NS - 1))
            assertTrue(policy.shouldDropDuringPlayback(3200, 900, nowNs))

            fixture.invoke("enforceLatencyLimit")
            assertEquals(149, fixture.queue.size)
            assertEquals(1L, fixture.get("droppedForLatency"))
            repeat(60) {
                if (fixture.queue.size > 98) fixture.invoke("enforceLatencyLimit")
            }
            fixture.invoke("enforceLatencyLimit") // Refresh totalQueuedMs after the last drop.
            assertEquals(98, fixture.queue.size)
            assertEquals(52L, fixture.get("droppedForLatency"))
            assertTrue((fixture.get("totalQueuedMs") as Long) <= 2100L)
        } finally {
            fixture.track.release()
        }
    }

    @Test fun explicitStopPlaysAtLeastOneHundredMillisecondsAfterThreeHundredMillisecondsIdle() {
        val fixture = createFixture(packetCount = 0, playbackStarted = false)
        try {
            val buffer = fixture.get("bufferProgress") as AudioBufferProgress
            buffer.written(19_200)
            fixture.set("streamEnded", AtomicBoolean(true))
            fixture.set("endingRequestedNs", AtomicLong(System.nanoTime()))

            fixture.invoke("maintainPlaybackBuffer")
            assertEquals(0, TestTrack.playCount)

            fixture.set("endingRequestedNs", AtomicLong(System.nanoTime() - 300_000_001L))
            fixture.invoke("maintainPlaybackBuffer")
            assertEquals(1, TestTrack.playCount)
            assertEquals(true, fixture.get("playbackStarted"))
        } finally {
            fixture.track.release()
        }
    }

    @Test fun stalledTailExitsAfterThePlaybackHeadStopsAndReportsRemainingFrames() {
        val fixture = createFixture(packetCount = 0, playbackStarted = true)
        try {
            val buffer = fixture.get("bufferProgress") as AudioBufferProgress
            buffer.written(19_200)
            fixture.set("streamEnded", AtomicBoolean(true))
            fixture.set("finalTailFlushed", AtomicBoolean(true))

            val start = 1_000_000_000L
            fixture.invoke("finishEndedStreamIfDrained", start)
            assertTrue(fixture.get("running") as Boolean)

            fixture.invoke("finishEndedStreamIfDrained", start + 1_500_000_000L)
            assertFalse(fixture.get("running") as Boolean)
            assertTrue(fixture.reports.any {
                it.contains("tail drain exit reason=playback-head-stalled") && it.contains("remainingFrames=4800")
            })
        } finally {
            fixture.track.release()
        }
    }

    @Test fun aMovingPlaybackHeadDrainsNormallyWithoutAnEarlyExitReport() {
        val fixture = createFixture(packetCount = 0, playbackStarted = true)
        try {
            val buffer = fixture.get("bufferProgress") as AudioBufferProgress
            buffer.written(19_200)
            fixture.set("streamEnded", AtomicBoolean(true))
            fixture.set("finalTailFlushed", AtomicBoolean(true))

            val start = 2_000_000_000L
            fixture.invoke("finishEndedStreamIfDrained", start)
            TestTrack.playbackHeadPosition = 4_800
            fixture.invoke("finishEndedStreamIfDrained", start + 500_000_000L)

            assertFalse(fixture.get("running") as Boolean)
            assertTrue(fixture.reports.none { it.contains("tail drain exit") })
        } finally {
            fixture.track.release()
        }
    }

    private fun createFixture(packetCount: Int, playbackStarted: Boolean): Fixture {
        TestTrack.reset()
        val plan = MediaAudioBuffer.plan(true, 48_000, 2, 4096, 500, ts7Music = true)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().build())
            .setAudioFormat(android.media.AudioFormat.Builder()
                .setSampleRate(48_000)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
                .build())
            .setBufferSizeInBytes(plan.trackBufferBytes)
            .build()
        val rendererType = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        val reports = ArrayList<String>()
        val renderer = rendererType.declaredConstructors.single { it.parameterCount == 14 }
            .apply { isAccessible = true }
            .newInstance(
                AudioFormat(AudioCodecKind.AAC_LC, 48_000, 2, 96, "media"),
                false, false, 0, 0, AudioFocusCoordinator(null, false, false),
                0, 500, { line: String -> reports += line }, 0L, false, null, false, null,
            )
        val fixture = Fixture(rendererType, renderer, track, reports)
        fixture.set("ts7Music", true)
        fixture.set("track", track)
        fixture.set("mappedChannel", AudioChannel.MEDIA)
        fixture.set("bytesPerSecond", 48_000 * 4)
        fixture.set("playbackStarted", playbackStarted)
        fixture.set("adaptiveBuffer", Ts7MusicBuffer(48_000, 4, plan.trackBufferBytes, 500))
        fixture.set("gapConcealer", Ts7MusicGapConcealer<Any>(48_000))
        fixture.queue = fixture.get("queue") as BlockingQueue<Any>
        val packetType = Class.forName("com.shilapi.xcertplay.media.AudioRenderer\$AudioPacket")
        val packetConstructor = packetType.declaredConstructors.single().apply { isAccessible = true }
        repeat(packetCount) { sequence ->
            val rtp = ByteArray(12)
            rtp[2] = (sequence shr 8).toByte()
            rtp[3] = sequence.toByte()
            fixture.queue.offer(packetConstructor.newInstance(
                rtp,
                sequence * 1024,
                sequence.toLong() * 1_000_000L,
                0L,
            ))
        }
        return fixture
    }

    private class Fixture(
        val rendererType: Class<*>,
        val renderer: Any,
        val track: AudioTrack,
        val reports: MutableList<String>,
    ) {
        lateinit var queue: BlockingQueue<Any>

        fun set(name: String, value: Any?) = rendererType.getDeclaredField(name)
            .apply { isAccessible = true }.set(renderer, value)

        fun get(name: String): Any? = rendererType.getDeclaredField(name)
            .apply { isAccessible = true }.get(renderer)

        fun invoke(name: String, vararg args: Any?): Any? = rendererType.declaredMethods
            .single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(renderer, *args)
    }

    @Implements(AudioTrack::class)
    class TestTrack {
        @Implementation fun getPlaybackHeadPosition() = playbackHeadPosition
        @Implementation fun getUnderrunCount() = 0
        @Implementation fun getBufferSizeInFrames() = 115_200
        @Implementation fun play() { playCount++ }
        @Implementation fun pause() {}
        @Implementation fun flush() {}
        @Implementation fun setPlaybackRate(rate: Int): Int = AudioTrack.SUCCESS
        @Implementation fun setBufferSizeInFrames(frames: Int): Int = frames

        companion object {
            var playCount = 0
            var playbackHeadPosition = 0
            fun reset() {
                playCount = 0
                playbackHeadPosition = 0
            }
        }
    }
}

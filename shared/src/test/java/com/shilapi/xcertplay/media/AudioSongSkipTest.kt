package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.util.concurrent.BlockingQueue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [AudioSongSkipTest.SkipTrack::class])
class AudioSongSkipTest {
    @Test fun skipFlushesOldGenerationAndRestoresTheMusicStartState() {
        SkipTrack.reset()
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
        val reports = ArrayList<String>()
        val rendererType = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        val renderer = rendererType.declaredConstructors.single { it.parameterCount == 14 }
            .apply { isAccessible = true }
            .newInstance(
                AudioFormat(AudioCodecKind.AAC_LC, 48_000, 2, 96, "media"),
                false, false, 0, 0, AudioFocusCoordinator(null, false, false),
                0, 500, { line: String -> reports += line }, 0L, false, null, false, null,
            )

        fun set(name: String, value: Any?) = rendererType.getDeclaredField(name).apply { isAccessible = true }.set(renderer, value)
        fun get(name: String): Any? = rendererType.getDeclaredField(name).apply { isAccessible = true }.get(renderer)
        fun invoke(name: String, vararg args: Any?): Any? {
            val method = rendererType.declaredMethods.single { it.name == name && it.parameterCount == args.size }
                .apply { isAccessible = true }
            return method.invoke(renderer, *args)
        }

        try {
            set("ts7Music", true)
            set("track", track)
            set("mappedChannel", AudioChannel.MEDIA)
            set("started", true)
            set("playbackStarted", true)
            set("prebufferBytes", 38_400)
            set("rebufferCount", 3)
            set("fadeApplied", true)
            set("bytesPerSecond", 48_000 * 4)
            set("totalWrittenFrames", 12_000L)

            val adaptive = Ts7MusicBuffer(48_000, 4, plan.trackBufferBytes, 500).also { it.rebuffer(1) }
            set("adaptiveBuffer", adaptive)
            set("startThresholdBytes", adaptive.targetBytes)
            val gapTracker = Ts7MusicGapConcealer<String>(48_000).also {
                it.accept(1, 0, "first", 0)
                it.accept(3, 2048, "third", 1)
            }
            set("gapConcealer", gapTracker)

            val rtp = ByteArray(16)
            invoke("submit", rtp, 0)
            invoke("requestSongSkip", CarPlayMediaButton.NEXT)
            invoke("submit", rtp, 1024)

            assertEquals(true, invoke("processPendingSongSkip"))

            @Suppress("UNCHECKED_CAST")
            val queue = get("queue") as BlockingQueue<Any>
            assertEquals(1, queue.size)
            val retainedGeneration = queue.peek().javaClass.getDeclaredField("generation")
                .apply { isAccessible = true }.getLong(queue.peek())
            assertEquals(1L, retainedGeneration)
            assertEquals(1, (get("packetsDroppedTotal") as java.util.concurrent.atomic.AtomicInteger).get())
            assertEquals(900 * 192, adaptive.targetBytes)
            assertEquals(2100 * 192, get("startThresholdBytes"))
            assertEquals(0, gapTracker.reorderedPacketCount)
            assertFalse(get("playbackStarted") as Boolean)
            assertEquals(0, get("prebufferBytes"))
            assertEquals(0, get("rebufferCount"))
            assertFalse(get("fadeApplied") as Boolean)
            assertEquals(0L, get("totalWrittenFrames"))
            assertEquals(1, SkipTrack.pauseCount)
            assertEquals(1, SkipTrack.flushCount)
            assertEquals(48_000, SkipTrack.lastPlaybackRate)
            assertTrue(reports.any { it.contains("TS7 music queue cleared reason=next") && it.contains("queuedPackets=1") })
        } finally {
            track.release()
        }
    }

    @Implements(AudioTrack::class)
    class SkipTrack {
        @Implementation fun getPlaybackHeadPosition() = 0
        @Implementation fun getUnderrunCount() = 0
        @Implementation fun getBufferSizeInFrames() = activeBufferFrames
        @Implementation fun pause() { pauseCount++ }
        @Implementation fun flush() { flushCount++ }
        @Implementation fun setPlaybackRate(rate: Int): Int { lastPlaybackRate = rate; return AudioTrack.SUCCESS }
        @Implementation fun setBufferSizeInFrames(frames: Int): Int { activeBufferFrames = frames; return frames }

        companion object {
            var activeBufferFrames = 115_200
            var pauseCount = 0
            var flushCount = 0
            var lastPlaybackRate = 0
            fun reset() {
                activeBufferFrames = 115_200
                pauseCount = 0
                flushCount = 0
                lastPlaybackRate = 0
            }
        }
    }
}

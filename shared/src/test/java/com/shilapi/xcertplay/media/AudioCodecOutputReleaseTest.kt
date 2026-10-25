package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaCodec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE,
    shadows = [AudioCodecOutputReleaseTest.OutputCodec::class, AudioCodecOutputReleaseTest.OutputTrack::class])
class AudioCodecOutputReleaseTest {
    @Test fun copiedPcmReleasesTheCodecBeforeEveryTrackWrite() {
        for (audioType in listOf("media", "default", "compatibility")) {
            drain(audioType)
            assertEquals(listOf("copy", "release", "write"), OutputCodec.events)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), OutputTrack.written)
        }
    }

    @Test fun nonTs7PlaybackRetainsTheLegacySingleBlockingWrite() {
        drain("default", pcmBytes = 10_000, playbackStarted = true)
        assertEquals(listOf(10_000), OutputTrack.writeSizes)
    }

    @Test fun nonTs7PrebufferRetainsTheLegacyTwoKilobyteChunks() {
        drain("default", pcmBytes = 10_000, playbackStarted = false)
        assertEquals(listOf(2048, 2048, 2048, 2048, 1808), OutputTrack.writeSizes)
    }

    @Test fun ts7PlaybackWritesAtMostFortyMillisecondsPerBlockingCall() {
        drain("media", pcmBytes = 10_000, playbackStarted = true, ts7Music = true)
        assertEquals(listOf(7680, 2320), OutputTrack.writeSizes)
    }

    @Test fun copyFailureStillReleasesTheOutputWithoutWriting() {
        drain("media", failCopy = true)
        assertEquals(listOf("copy", "release"), OutputCodec.events)
        assertNull(OutputTrack.written)
    }

    @Test fun aTrackWriteFailureCannotHoldTheCodecOutput() {
        drain("media", failWrite = true)
        assertEquals(listOf("copy", "release", "write"), OutputCodec.events)
    }

    @Test fun aLostAacBurstWritesSilenceAfterReleasingTheDecoderAndBeforeCurrentPcm() {
        drain("media", missingPackets = 18)
        val all = OutputTrack.allWrites.toByteArray()
        assertEquals(18 * 1024 * 4 + 4, all.size)
        assertTrue(all.take(all.size - 4).all { it == 0.toByte() })
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), all.takeLast(4).toByteArray())
    }

    private fun drain(
        audioType: String,
        failCopy: Boolean = false,
        failWrite: Boolean = false,
        missingPackets: Int = 0,
        pcmBytes: Int = 4,
        playbackStarted: Boolean = false,
        ts7Music: Boolean? = null,
    ) {
        OutputCodec.events.clear()
        OutputCodec.available = true
        OutputCodec.failCopy = failCopy
        OutputCodec.outputPcm = ByteArray(pcmBytes) { (it + 1).toByte() }
        OutputTrack.failWrite = failWrite
        OutputTrack.written = null
        OutputTrack.writeSizes.clear()
        OutputTrack.allWrites.reset()
        OutputCodec.timestampUs = if (missingPackets > 0) (missingPackets + 1) * 1024L * 1_000_000 / 48_000 else 12345
        val codec = MediaCodec.createDecoderByType("audio/mp4a-latm")
        val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().build())
            .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(48_000)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(4096).build()
        val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        val constructor = type.declaredConstructors.single { !it.isSynthetic }.apply { isAccessible = true }
        val arguments = mutableListOf<Any?>(
            AudioFormat(AudioCodecKind.AAC_LC, 48_000, 2, 96, audioType), false, false, 0, 0,
            AudioFocusCoordinator(null, false, false), 0, 500,
        )
        constructor.parameterTypes.drop(arguments.size).forEach { parameter ->
            arguments.add(when (parameter) {
                Long::class.javaPrimitiveType -> 0L
                Boolean::class.javaPrimitiveType -> false
                EchoReference::class.java -> null
                AudioManager::class.java -> null
                else -> {
                    check(parameter.name == "kotlin.jvm.functions.Function1") { "Unexpected renderer parameter: $parameter" }
                    val report: (String) -> Unit = {}
                    report
                }
            })
        }
        val renderer = constructor.newInstance(*arguments.toTypedArray())
        fun set(name: String, value: Any) = type.getDeclaredField(name).apply { isAccessible = true }.set(renderer, value)
        set("playbackStarted", playbackStarted)
        set("startThresholdBytes", 100_000)
        if (ts7Music != null) {
            set("ts7Music", ts7Music)
            set("bytesPerSecond", 48_000 * 4)
        }
        if (missingPackets > 0) {
            val gaps = Ts7MusicGapConcealer<Any>(48_000)
            gaps.accept(0, 0, Any(), 0L)
            gaps.accept(missingPackets + 1, (missingPackets + 1) * 1024, Any(), 40_000_000L)
            set("gapConcealer", gaps)
        }
        set("track", track)
        set("fadeApplied", true)
        try {
            type.getDeclaredMethod("drainCodec", MediaCodec::class.java).apply { isAccessible = true }.invoke(renderer, codec)
            assertFalse("expected injected failure", failCopy || failWrite)
        } catch (error: InvocationTargetException) {
            assertTrue(error.cause is IllegalStateException && (failCopy || failWrite))
        } finally {
            track.release()
            codec.release()
        }
    }

    @Implements(MediaCodec::class)
    class OutputCodec : ShadowMediaCodec() {
        @Implementation override fun native_dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeout: Long): Int {
            if (!available) return MediaCodec.INFO_TRY_AGAIN_LATER
            available = false
            info.set(2, outputPcm.size, timestampUs, 0)
            return 0
        }
        @Implementation override fun getBuffer(input: Boolean, index: Int): ByteBuffer {
            events.add("copy")
            check(!failCopy) { "injected copy failure" }
            return ByteBuffer.wrap(byteArrayOf(9, 9) + outputPcm + byteArrayOf(9))
        }
        @Implementation override fun releaseOutputBuffer(index: Int, render: Boolean) { events.add("release") }
        companion object {
            val events = mutableListOf<String>()
            var available = true
            var failCopy = false
            var timestampUs = 12345L
            var outputPcm = byteArrayOf(1, 2, 3, 4)
        }
    }

    @Implements(AudioTrack::class)
    class OutputTrack {
        @Implementation fun write(bytes: ByteArray, offset: Int, size: Int, mode: Int): Int {
            assertTrue("decoder must be released before PCM or silence writes", OutputCodec.events.contains("release"))
            OutputCodec.events.add("write")
            check(!failWrite) { "injected write failure" }
            written = bytes.copyOfRange(offset, offset + size)
            writeSizes += size
            allWrites.write(bytes, offset, size)
            return size
        }
        companion object {
            val allWrites = java.io.ByteArrayOutputStream()
            var written: ByteArray? = null
            var failWrite = false
            val writeSizes = mutableListOf<Int>()
        }
    }
}

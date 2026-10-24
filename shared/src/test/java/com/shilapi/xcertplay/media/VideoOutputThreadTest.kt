package com.shilapi.xcertplay.media

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCrypto
import android.media.MediaFormat
import android.view.Surface
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaCodec
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Low-latency decoding releases decoded frames from a thread of its own, which stops with the decoder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class VideoOutputThreadTest {
    private val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1E)
    private val pps = byteArrayOf(0x68, 0xCE.toByte())
    private val avcC = byteArrayOf(1, 0x42, 0xC0.toByte(), 0x1E, 0xFF.toByte(), 0xE1.toByte(), 0, sps.size.toByte()) +
        sps + byteArrayOf(1, 0, pps.size.toByte()) + pps
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21)
    private val uncaught = ConcurrentLinkedQueue<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    private object PassThroughCodec : ShadowMediaCodec.CodecConfig.Codec {
        override fun process(input: ByteBuffer, output: ByteBuffer) = Unit
        override fun onConfigured(format: MediaFormat?, surface: Surface?, crypto: MediaCrypto?, flags: Int) = Unit
    }

    private class RecordingCodec(
        private val inputs: MutableList<ByteArray>,
        private val markerQueued: CountDownLatch,
    ) : ShadowMediaCodec.CodecConfig.Codec {
        override fun process(input: ByteBuffer, output: ByteBuffer) {
            val bytes = ByteArray(input.remaining())
            input.duplicate().get(bytes)
            inputs += bytes
            if (bytes.contentEquals(byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte()))) markerQueued.countDown()
        }

        override fun onConfigured(format: MediaFormat?, surface: Surface?, crypto: MediaCrypto?, flags: Int) = Unit
    }

    @Before fun setUp() {
        ShadowMediaCodec.addDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, ShadowMediaCodec.CodecConfig(64 * 1024, 64 * 1024, PassThroughCodec))
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught += error }
    }

    @After fun tearDown() {
        ShadowMediaCodec.clearCodecs()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    @Test fun theOutputThreadRendersFramesAndStopsWithTheDecoder() {
        val reports = CopyOnWriteArrayList<String>()
        val rendered = CountDownLatch(1)
        val sink = AndroidMediaSink(surface = null, vendorLowLatencyDecoder = true)
        sink.setVideoDiagnosticHandler(MAIN) { message ->
            reports += message
            if (message == "first frame rendered") rendered.countDown()
        }
        val surface = Surface(SurfaceTexture(0))
        sink.setSurface(MAIN, surface)
        sink.onVideoConfig(MAIN, avcC)
        sink.onVideoFrame(MAIN, idr)
        assertTrue("a decoded frame was rendered", rendered.await(5, TimeUnit.SECONDS))
        assertTrue(reports.toString(), reports.any { it.startsWith("decoder=") && it.endsWith(" outputThread=true") })

        val decoder = mainDecoder(sink)
        val output = checkNotNull(decoder.field<Thread?>("outputThread")) { "no output thread" }
        assertTrue(output.isAlive)

        // Losing the surface releases the codec the output thread is waiting on.
        assertTrue(sink.beginSurfaceDetach(surface, parkMain = false).await())
        assertNull(decoder.field<MediaCodec?>("decoder"))

        sink.close()
        assertTrue(sink.awaitVideoReleased(5_000))
        output.join(5_000)
        assertFalse("the output thread exits with the worker", output.isAlive)
        assertTrue(uncaught.toString(), uncaught.isEmpty())
        assertFalse(reports.toString(), reports.any { it.startsWith("decoder failed") })
    }

    @Test fun withoutLowLatencyDecodingTheWorkerDrainsItsOwnOutput() {
        val sink = AndroidMediaSink(surface = null)
        try {
            sink.setSurface(MAIN, Surface(SurfaceTexture(0)))
            sink.onVideoConfig(MAIN, avcC)
            sink.onVideoFrame(MAIN, idr)
            assertTrue(sink.beginSurfaceDetach(Surface(SurfaceTexture(0)), parkMain = false).await())
            assertNull(mainDecoder(sink).field<Thread?>("outputThread"))
        } finally {
            sink.close()
            sink.awaitVideoReleased(5_000)
        }
    }

    @Test fun separateAudIsQueuedWithoutAnExtraFrameBookkeepingSlot() {
        val inputs = CopyOnWriteArrayList<ByteArray>()
        val reports = CopyOnWriteArrayList<String>()
        val markerQueued = CountDownLatch(1)
        val decoderReady = CountDownLatch(1)
        val markerDropped = CountDownLatch(1)
        ShadowMediaCodec.addDecoder(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            ShadowMediaCodec.CodecConfig(64 * 1024, 64 * 1024, RecordingCodec(inputs, markerQueued)),
        )
        val sink = AndroidMediaSink(surface = null, h264FrameEndMarker = H264FrameEndMarkerMode.SEPARATE)
        try {
            sink.setVideoDiagnosticHandler(MAIN) { message ->
                reports += message
                if (message.startsWith("decoder=")) decoderReady.countDown()
                if (message.startsWith("H.264 frame end marker dropped:")) markerDropped.countDown()
            }
            val surface = Surface(SurfaceTexture(0))
            sink.setSurface(MAIN, surface)
            sink.onVideoConfig(MAIN, avcC)
            sink.onVideoFrame(MAIN, idr)

            assertTrue("the AUD input was queued", markerQueued.await(5, TimeUnit.SECONDS))
            assertTrue("decoder configuration was reported", decoderReady.await(5, TimeUnit.SECONDS))
            assertTrue(inputs.toString(), inputs.any { it.contentEquals(idr) })
            assertEquals(1, mainDecoder(sink).field<Int>("queuedSlot"))
            assertTrue(reports.toString(), reports.any { it.startsWith("decoder=") && it.contains("frameEndMarker=separate") })

            sink.onVideoFrame(MAIN, ByteArray(0))
            assertTrue("recovery disables the marker for this decoder", markerDropped.await(5, TimeUnit.SECONDS))
            assertEquals(H264FrameEndMarkerMode.OFF, mainDecoder(sink).field<H264FrameEndMarkerMode>("frameEndMarkerActive"))
        } finally {
            sink.close()
            sink.awaitVideoReleased(5_000)
        }
    }

    @Test fun configurationFailureDisablesTheMarkerForTheDecoderLifetime() {
        val dropped = CountDownLatch(1)
        ShadowMediaCodec.addDecoder(MediaFormat.MIMETYPE_VIDEO_AVC,
            ShadowMediaCodec.CodecConfig(64 * 1024, 64 * 1024, object : ShadowMediaCodec.CodecConfig.Codec {
                override fun process(input: ByteBuffer, output: ByteBuffer) = Unit
                override fun onConfigured(format: MediaFormat?, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
                    throw IllegalStateException("test configure failure")
                }
            }))
        val sink = AndroidMediaSink(surface = null, h264FrameEndMarker = H264FrameEndMarkerMode.APPEND)
        try {
            sink.setVideoDiagnosticHandler(MAIN) { message ->
                if (message.startsWith("H.264 frame end marker dropped: decoder configuration failed")) dropped.countDown()
            }
            sink.setSurface(MAIN, Surface(SurfaceTexture(0)))
            sink.onVideoConfig(MAIN, avcC)
            assertTrue("configuration failure drops the marker", dropped.await(5, TimeUnit.SECONDS))
            assertEquals(H264FrameEndMarkerMode.OFF, mainDecoder(sink).field<H264FrameEndMarkerMode>("frameEndMarkerActive"))
        } finally {
            sink.close()
            sink.awaitVideoReleased(5_000)
        }
    }

    @Test fun rewrittenSpsDiagnosticsExplainTheDecoderInput() {
        val sps = hex("2764001fac56805005b9")
        val pps = byteArrayOf(0x68, 0xce.toByte())
        val codecData = byteArrayOf(1, sps[1], sps[2], sps[3], 0xff.toByte(), 0xe1.toByte(), 0, sps.size.toByte()) +
            sps + byteArrayOf(1, 0, pps.size.toByte()) + pps
        val reports = CopyOnWriteArrayList<String>()
        val decoderReady = CountDownLatch(1)
        val sink = AndroidMediaSink(surface = null, lowDelayH264Sps = true)
        try {
            sink.setVideoDiagnosticHandler(MAIN) { message ->
                reports += message
                if (message.startsWith("decoder=")) decoderReady.countDown()
            }
            sink.setSurface(MAIN, Surface(SurfaceTexture(0)))
            sink.onVideoConfig(MAIN, codecData)
            sink.onVideoFrame(MAIN, idr)

            assertTrue("decoder configuration was reported", decoderReady.await(5, TimeUnit.SECONDS))
            assertTrue(reports.toString(), reports.any { it.startsWith("decoder=") && it.contains("lowDelaySps=true") })
            assertTrue(reports.toString(), reports.any {
                it.startsWith("H.264 SPS summary: result=rewritten") &&
                    it.contains("profile_idc=100") && it.contains("level_idc=31") &&
                    it.contains("vui_present=false") && it.contains("bitstream_restriction_present=false") &&
                    it.contains("sps=27 64 00 1f ac 56 80 50 05 b9")
            })
        } finally {
            sink.close()
            sink.awaitVideoReleased(5_000)
        }
    }

    private fun mainDecoder(sink: AndroidMediaSink): Any =
        checkNotNull(sink.field<Map<Int, Any>>("videoDecoders")[MAIN]) { "no main decoder" }

    @Suppress("UNCHECKED_CAST")
    private fun <T> Any.field(name: String): T {
        var type: Class<*>? = javaClass
        while (type != null) {
            try {
                return type.getDeclaredField(name).apply { isAccessible = true }.get(this) as T
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        error("no field $name on $javaClass")
    }

    private companion object {
        const val MAIN = 110

        fun hex(value: String) = ByteArray(value.length / 2) {
            value.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }
}

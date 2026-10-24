package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class H264FrameEndMarkerTest {
    private val start = byteArrayOf(0, 0, 0, 1)
    private val aud = start + byteArrayOf(0x09, 0xF0.toByte())
    private val idr = start + byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x21)

    @Test fun appendQueuesOneAudAtTheEndAndRemovesTheLeadingAud() {
        val prepared = H264FrameEndMarkerBytes.prepare(
            aud + idr,
            H264FrameEndMarkerMode.APPEND,
        )

        assertArrayEquals(idr, prepared.referenceBytes)
        assertArrayEquals(idr + aud, prepared.frameBytes)
        assertNull(prepared.separateMarker)
        assertTrue(MediaCodecSupport.isRandomAccess(prepared.referenceBytes, VideoCodec.H264))
    }

    @Test fun separateQueuesOnlyTheAudAsASecondInputAndRemovesTheLeadingAud() {
        val prepared = H264FrameEndMarkerBytes.prepare(
            aud + idr,
            H264FrameEndMarkerMode.SEPARATE,
        )

        assertArrayEquals(idr, prepared.referenceBytes)
        assertArrayEquals(idr, prepared.frameBytes)
        assertArrayEquals(aud, prepared.separateMarker)
    }

    @Test fun offKeepsTheOriginalAccessUnitWithoutASecondInput() {
        val source = aud + idr
        val prepared = H264FrameEndMarkerBytes.prepare(source, H264FrameEndMarkerMode.OFF)

        assertSame(source, prepared.referenceBytes)
        assertSame(source, prepared.frameBytes)
        assertNull(prepared.separateMarker)
    }

    @Test fun aFrameWithoutAnAudGetsTheSelectedEndMarker() {
        val appended = H264FrameEndMarkerBytes.prepare(idr, H264FrameEndMarkerMode.APPEND)
        val separate = H264FrameEndMarkerBytes.prepare(idr, H264FrameEndMarkerMode.SEPARATE)

        assertArrayEquals(idr, appended.referenceBytes)
        assertArrayEquals(idr + aud, appended.frameBytes)
        assertArrayEquals(idr, separate.frameBytes)
        assertArrayEquals(aud, separate.separateMarker)
    }
}

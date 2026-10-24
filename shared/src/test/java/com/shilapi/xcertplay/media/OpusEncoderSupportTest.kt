package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class OpusEncoderSupportTest {
    @Test fun platformCapabilityDoesNotInitializeTheSoftwareProbe() {
        assertTrue(OpusEncoderSupport.isAvailable(
            sdkInt = 29,
            platformAvailable = { true },
            software = { error("Software probe must not run") },
        ))
    }

    @Test fun missingPlatformEncoderKeepsOpusWhenTheBundledEncoderProducesAPacket() {
        val software = SoftwareOpusEncoder(48_000)
        assertTrue(OpusEncoderSupport.isAvailable(sdkInt = 29, platformAvailable = { false }, software = { software }))
        assertFalse("the probe releases its encoder", software.available)
    }

    @Test fun unreadablePlatformCodecListStillUsesTheBundledEncoder() {
        assertTrue(OpusEncoderSupport.isAvailable(
            sdkInt = 29,
            platformAvailable = { throw IllegalStateException("codec list unavailable") },
            software = { SoftwareOpusEncoder(48_000) },
        ))
    }

    @Test fun unavailableSoftwareInitializationDoesNotAdvertiseAnEncoderOrThrow() {
        val software = SoftwareOpusEncoder(48_000, complexity = 11)
        assertFalse(software.available)
        assertFalse(OpusEncoderSupport.isAvailable(platformAvailable = { false }, software = { software }))
        assertFalse(OpusEncoderSupport.isAvailable(
            platformAvailable = { false }, software = { throw IllegalStateException("initialization failed") },
        ))
    }

    @Test fun encoderThatCannotProduceAPacketIsNotAUsableCapability() {
        val software = ProbeEncoder { emptyList() }
        assertFalse(OpusEncoderSupport.isAvailable(sdkInt = 29, platformAvailable = { false }, software = { software }))
        assertEquals(SoftwareOpusEncoder.FRAME_BYTES, software.frameBytes)
        assertTrue(software.closed)
    }

    @Test fun failedEncodingProbeIsContainedAndClosed() {
        val software = ProbeEncoder { throw IllegalStateException("encode failed") }
        assertFalse(OpusEncoderSupport.isAvailable(sdkInt = 29, platformAvailable = { false }, software = { software }))
        assertTrue(software.closed)
    }

    @Test fun preAndroidTenSkipsThePlatformProbeAndUsesSoftware() {
        var platformCalls = 0
        var softwareCalls = 0
        val software = ProbeEncoder { listOf(byteArrayOf(1)) }
        assertTrue(OpusEncoderSupport.isAvailable(
            sdkInt = 28,
            platformAvailable = { platformCalls++; true },
            software = { softwareCalls++; software },
        ))
        assertEquals(0, platformCalls)
        assertEquals(1, softwareCalls)
        assertTrue(software.closed)
    }

    @Test fun cachedCapabilityRunsItsProbeOnlyOnce() {
        var platformCalls = 0
        val available = CachedOpusEncoderAvailability(
            sdkInt = 29,
            platformAvailable = { platformCalls++; true },
            software = { error("Software probe must not run") },
        )
        assertTrue(available.isAvailable())
        assertTrue(available.isAvailable())
        assertEquals(1, platformCalls)
    }

    @Test fun microphoneNotOfferedSkipsCapabilityProbe() {
        var probes = 0
        assertFalse(OpusEncoderSupport.isAvailableIfOffered(microphoneOffered = false) { probes++; true })
        assertEquals(0, probes)
        assertTrue(OpusEncoderSupport.isAvailableIfOffered(microphoneOffered = true) { probes++; true })
        assertEquals(1, probes)
    }

    private class ProbeEncoder(private val encodeFrame: () -> List<ByteArray>) : MicrophoneOpusEncoder {
        override val implementation = "probe"
        override val available = true
        var closed = false
        var frameBytes = 0
        override fun encode(pcm: ByteArray): List<ByteArray> {
            frameBytes = pcm.size
            return encodeFrame()
        }
        override fun close() { closed = true }
    }
}

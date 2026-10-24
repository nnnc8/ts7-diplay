package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioDiagnosticsTest {
    @Test fun microphoneInputDevicesIncludeTypeProductAndRouteState() {
        assertEquals(
            "inputDevices=[type=15 product=Built-in_Mic routed=true;type=3 product=USB_Microphone routed=false]",
            formatMicrophoneInputDevices(
                listOf(
                    MicrophoneInputDeviceDiagnostic(15, "Built-in Mic", routed = true),
                    MicrophoneInputDeviceDiagnostic(3, "USB Microphone", routed = false),
                ),
            ),
        )
    }

    @Test fun microphoneInputDeviceListCanBeEmpty() {
        assertEquals("inputDevices=[]", formatMicrophoneInputDevices(emptyList()))
    }

    @Test fun audioOutputMetadataIncludesNativeRateBufferSizeAndDecoderName() {
        assertEquals(
            "nativeRate=48000 framesPerBuffer=256 decoderName=c2.android.aac.decoder",
            audioOutputDiagnostics(
                nativeRate = { 48_000 },
                framesPerBuffer = { "256" },
                decoderName = { "c2.android.aac.decoder" },
            ),
        )
    }

    @Test fun audioOutputMetadataContainsPlatformPropertyFailures() {
        assertEquals(
            "nativeRate=unknown framesPerBuffer=unknown",
            audioOutputDiagnostics(
                nativeRate = { throw IllegalStateException("unavailable") },
                framesPerBuffer = { "256" },
                decoderName = { null },
            ),
        )
    }
}

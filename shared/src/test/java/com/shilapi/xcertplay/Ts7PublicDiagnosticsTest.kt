package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class Ts7PublicDiagnosticsTest {
    @Before fun clearLog() { ShadowLog.clear() }

    @Test fun rawProtocolMediaIdentityAndRoadValuesNeverReachLogcat() {
        val lines = listOf(
            "TRACE IAP2 tx frame=SENTINEL",
            "PHONE diagnostic SENTINEL",
            "audio payload=SENTINEL", "audio wire=SENTINEL", "audio head=SENTINEL",
            "certificate=SENTINEL", "challenge=SENTINEL", "signature=SENTINEL",
            "privateKey=SENTINEL", "pairRecord=SENTINEL", "token=SENTINEL",
            "ssid=SENTINEL", "passphrase=SENTINEL", "deviceName=SENTINEL",
            "controllerId=SENTINEL", "serialNumber=SENTINEL", "road=SENTINEL",
            "usbTransport=SENTINEL", "transportIdentifier=SENTINEL", "hostId=SENTINEL",
            "guidance=SENTINEL", "operation failed: SENTINEL", "status\nSENTINEL",
        )
        for (line in lines) {
            assertNull(line, PublicDiagnostics.redact(line))
            PublicLog.i(TAG, line)
        }
        assertTrue(ShadowLog.getLogsForTag(TAG).isEmpty())
    }

    @Test fun fixedStageAndMediaCountersRemainUseful() {
        val lines = listOf(
            "AUTH_BLOCKED", "STEP mfi/ready: MFi authentication provider is ready",
            "airplay control rx bodyBytes=512", "touch tx contacts=1 reportBytes=8",
            "audio stats codec=AAC_LC rx=215 dropped=0 underruns=3 queue=2",
            "video stats rx=30fps shown=30fps maxGap=100ms recoveries=0",
        )
        for (line in lines) {
            assertEquals(line, PublicDiagnostics.redact(line))
            PublicLog.i(TAG, line)
        }
        assertEquals(lines, ShadowLog.getLogsForTag(TAG).map { it.msg })
    }

    @Test fun addressesEndpointsAndIdentifiersAreRemovedBeforeLogcat() {
        val line = "connected peer=C0:A6:00:29:58:0A ip=192.168.31.71 " +
            "id=0123456789abcdef0123456789abcdef ipv6=fe80::1234:5678:abcd:9%p2p0 " +
            "server=https://sentinel.example/path"
        PublicLog.i(TAG, line)
        val output = ShadowLog.getLogsForTag(TAG).single().msg
        assertTrue(output.startsWith("connected"))
        for (value in listOf("C0:A6", "192.168", "0123456789", "fe80", "sentinel.example")) {
            assertFalse(value, output.contains(value))
        }
    }

    @Test fun arbitraryExceptionMessagesCausesAndStacksDoNotReachLogcat() {
        PublicLog.w(TAG, "audio decoder configuration failed", IOException("SENTINEL", SecurityException("CAUSE_SENTINEL")))
        val output = ShadowLog.getLogsForTag(TAG).single()
        assertEquals("audio decoder configuration failed code=CONNECTION_IO_FAILED", output.msg)
        assertNull(output.throwable)
    }

    @Test fun failureCodesNeverIncorporateArbitraryMessages() {
        assertEquals("AUTH_BLOCKED", PublicDiagnostics.failureCode(AuthBlockedException()))
        assertEquals("PERMISSION_DENIED", PublicDiagnostics.failureCode(SecurityException("SENTINEL")))
        assertEquals("CONFIG_INVALID", PublicDiagnostics.failureCode(IllegalArgumentException("SENTINEL")))
        assertEquals("CONNECTION_IO_FAILED", PublicDiagnostics.failureCode(IOException("SENTINEL")))
        assertEquals("CONNECTION_FAILED", PublicDiagnostics.failureCode(IllegalStateException("SENTINEL")))
        assertEquals("CONNECTION_FAILED", PublicDiagnostics.failureCode("SENTINEL"))
        assertEquals("WIFI_RESET_REQUIRED", PublicDiagnostics.failureCode("WIFI_RESET_REQUIRED"))
        assertEquals("HOTSPOT_SECURITY_UNSUPPORTED", PublicDiagnostics.failureCode(IOException("HOTSPOT_SECURITY_UNSUPPORTED")))
    }

    @Test fun publicProfileKeepsVendorWorkersAndSensitiveCapturesDisabled() {
        assertFalse(Ts7PublicProfile.BYD_INTEGRATION_ENABLED)
        assertFalse(Ts7PublicProfile.RAW_PROTOCOL_TRACES_ENABLED)
        assertFalse(Ts7PublicProfile.SENSITIVE_CAPTURES_ENABLED)
    }

    @Test fun originalControllerWrappersPreserveOnlyExactManagerCodes() {
        for (code in listOf("HOTSPOT_SECURITY_UNSUPPORTED", "HOTSPOT_RADIO_INFO_UNAVAILABLE")) {
            assertEquals(code, PublicDiagnostics.failureCode(IOException("private outer text", IOException(code))))
        }
        assertEquals("CONNECTION_IO_FAILED", PublicDiagnostics.failureCode(IOException("outer", IOException("SENTINEL"))))
    }

    @Test fun cyclicExceptionCausesCannotLoopOrLeakText() {
        val outer = IOException("OUTER_SENTINEL")
        val inner = IOException("INNER_SENTINEL")
        outer.initCause(inner); inner.initCause(outer)
        assertEquals("CONNECTION_IO_FAILED", PublicDiagnostics.failureCode(outer))
    }

    @Test fun causeInspectionHasAnEightNodeLimit() {
        var error: Throwable = IOException("HOTSPOT_SECURITY_UNSUPPORTED")
        repeat(8) { error = IOException("SENTINEL", error) }
        assertEquals("CONNECTION_IO_FAILED", PublicDiagnostics.failureCode(error))
    }

    private companion object { const val TAG = "Ts7PrivacyTest" }
}

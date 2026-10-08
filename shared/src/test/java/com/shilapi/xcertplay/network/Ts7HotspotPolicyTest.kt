package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.util.BitSet
import org.junit.Assert.*
import org.junit.Test

class Ts7HotspotPolicyTest {
    private fun bits(vararg values: Int) = BitSet().apply { values.forEach(::set) }

    @Test fun bothDocumentedLegacyPskRepresentationsAreAccepted() {
        for (keys in listOf(bits(1), bits(4), bits(1, 4))) {
            assertEquals(Iap2WirelessSecurity.WPA_WPA2, Ts7HotspotPolicy.legacySecurity(keys))
        }
    }

    @Test fun openEmptyAndUnknownSecurityFailClosed() {
        for (keys in listOf(bits(), bits(0), bits(0, 4), bits(2), bits(4, 8), bits(9))) {
            try { Ts7HotspotPolicy.legacySecurity(keys); fail("must reject unsupported security") }
            catch (expected: IOException) { assertEquals("HOTSPOT_SECURITY_UNSUPPORTED", expected.message) }
        }
    }

    @Test fun apMeasurementWinsAndFrameworkChannelIsNotAStationGuess() {
        assertEquals(6, Ts7HotspotPolicy.channel(2437, 36))
        assertEquals(149, Ts7HotspotPolicy.channel(5745, 0))
        assertEquals(11, Ts7HotspotPolicy.channel(null, 11))
    }

    @Test fun absentInvalidAndUnmappableRadioInfoNeverFallsBackTo36() {
        for (value in listOf(null to 0, null to 256, 1000 to 36)) {
            try { Ts7HotspotPolicy.channel(value.first, value.second); fail("must not guess AP channel") }
            catch (expected: IOException) { assertEquals("HOTSPOT_RADIO_INFO_UNAVAILABLE", expected.message) }
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.util.BitSet

/** Android 8.1 LOHS reports PSK as either WPA_PSK (1) or WPA2_PSK (4).
 * These are framework constants, not observed TS7 key-management measurements.
 */
internal object Ts7HotspotPolicy {
    fun legacySecurity(bits: BitSet): Iap2WirelessSecurity {
        val unsupported = bits.clone() as BitSet
        unsupported.clear(1)
        unsupported.clear(4)
        if ((!bits[1] && !bits[4]) || !unsupported.isEmpty) {
            throw IOException("HOTSPOT_SECURITY_UNSUPPORTED")
        }
        return Iap2WirelessSecurity.WPA_WPA2
    }

    fun channel(frequencyMHz: Int?, configuredChannel: Int): Int {
        val measured = frequencyMHz?.let(::wifiFrequencyMhzToChannel)
        if (frequencyMHz != null && measured == null) {
            throw IOException("HOTSPOT_RADIO_INFO_UNAVAILABLE")
        }
        return (measured ?: configuredChannel.takeIf { it > 0 })
            ?.takeIf { it in 1..255 }
            ?: throw IOException("HOTSPOT_RADIO_INFO_UNAVAILABLE")
    }
}

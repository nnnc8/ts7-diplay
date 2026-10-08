package com.shilapi.xcertplay

import android.net.wifi.WifiConfiguration
import com.shilapi.xcertplay.network.LocalOnlyHotspotManager
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
class Ts7LegacyHotspotConfigurationTest {
    private val manager get() = LocalOnlyHotspotManager(RuntimeEnvironment.getApplication())

    private fun security(vararg bits: Int): Iap2WirelessSecurity {
        val configuration = WifiConfiguration().apply {
            allowedKeyManagement.clear()
            bits.forEach { allowedKeyManagement.set(it) }
        }
        val method = LocalOnlyHotspotManager::class.java.getDeclaredMethod("mapWifiConfigurationSecurity", WifiConfiguration::class.java)
        method.isAccessible = true
        return method.invoke(manager, configuration) as Iap2WirelessSecurity
    }

    @Test fun actualApi27DispatchAcceptsBothFrameworkPskRepresentations() {
        assertEquals(Iap2WirelessSecurity.WPA_WPA2, security(1))
        assertEquals(Iap2WirelessSecurity.WPA_WPA2, security(4))
    }

    @Test fun actualApi27DispatchRejectsOpenAndUnknownBits() {
        for (bits in listOf(intArrayOf(0), intArrayOf(2), intArrayOf(4, 9))) {
            try { security(*bits); fail("must fail closed") }
            catch (error: InvocationTargetException) { assertEquals("HOTSPOT_SECURITY_UNSUPPORTED", error.cause?.message) }
        }
    }

    @Test @Config(sdk = [32]) fun originalUpstreamMappingRemainsDistinctOutsideTs7Patch() {
        assertEquals(Iap2WirelessSecurity.WPA_WPA2, security(4))
        try { security(1); fail("original upstream does not map WPA_PSK") }
        catch (error: InvocationTargetException) { assertTrue(error.cause is IOException) }
    }

    @Test fun securedPassphraseValidationRejectsEmptyShortAndOverlongInputs() {
        val method = LocalOnlyHotspotManager::class.java.getDeclaredMethod("validatePassphrase", Iap2WirelessSecurity::class.java, String::class.java)
        method.isAccessible = true
        for (value in listOf(null, "short", "x".repeat(64))) {
            try { method.invoke(manager, Iap2WirelessSecurity.WPA_WPA2, value); fail("invalid PSK accepted") }
            catch (error: InvocationTargetException) { assertEquals("HOTSPOT_SECURITY_UNSUPPORTED", error.cause?.message) }
        }
        assertEquals("test-only-passphrase", method.invoke(manager, Iap2WirelessSecurity.WPA_WPA2, "test-only-passphrase"))
    }
}

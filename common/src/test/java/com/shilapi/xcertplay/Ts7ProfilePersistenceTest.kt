package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class Ts7ProfilePersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Before fun clearPreferences() {
        prefs.edit().clear().apply()
    }

    @Test fun lowDelayH264SpsDefaultsOnOnlyForTs7AndSavedValueWins() {
        assertTrue(AirPlayPersistence.loadLowDelayH264Sps(context, ts7 = true))
        assertFalse(AirPlayPersistence.loadLowDelayH264Sps(context, ts7 = false))

        AirPlayPersistence.saveLowDelayH264Sps(context, enabled = false)
        assertFalse(AirPlayPersistence.loadLowDelayH264Sps(context, ts7 = true))
        AirPlayPersistence.saveLowDelayH264Sps(context, enabled = true)
        assertTrue(AirPlayPersistence.loadLowDelayH264Sps(context, ts7 = false))
    }

    @Test fun wirelessBufferDefaultsTo500OnlyForTs7AndNeverChangesWiredDefault() {
        assertEquals(500, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true, ts7 = true))
        assertEquals(300, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true, ts7 = false))
        assertEquals(300, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false, ts7 = true))

        prefs.edit().putInt("media_buffer_ms", 1000).putInt("wired_media_buffer_ms", 50).apply()
        assertEquals(1000, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true, ts7 = true))
        assertEquals(50, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false, ts7 = true))
    }

    @Test fun wifiDirectMapsToManualOnTs7Only() {
        prefs.edit().putString("wireless_hotspot_mode", WirelessHotspotMode.WIFI_P2P.name).apply()
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context, ts7 = true))
        assertEquals(WirelessHotspotMode.MANUAL.name, prefs.getString("wireless_hotspot_mode", null))

        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P, ts7 = false)
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(context, ts7 = false))
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P, ts7 = true)
        assertEquals(WirelessHotspotMode.MANUAL.name, prefs.getString("wireless_hotspot_mode", null))
    }
}

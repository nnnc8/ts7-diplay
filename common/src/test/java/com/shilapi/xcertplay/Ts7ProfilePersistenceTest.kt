package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.media.H264FrameEndMarkerMode
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
        context.getSharedPreferences("diplay_split_screen", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("diplay_carplay_rotation", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("diplay_navigation_wheel", Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Test fun lowDelayH264SpsIsFixedOnForTs7AndSavedValueWinsElsewhere() {
        assertTrue(AirPlayPersistence.loadLowDelayH264Sps(context, ts7 = true))
        assertFalse(AirPlayPersistence.loadLowDelayH264Sps(context, ts7 = false))

        AirPlayPersistence.saveLowDelayH264Sps(context, enabled = false)
        assertTrue(AirPlayPersistence.loadLowDelayH264Sps(context, ts7 = true))
        assertFalse(AirPlayPersistence.loadLowDelayH264Sps(context, ts7 = false))
        AirPlayPersistence.saveLowDelayH264Sps(context, enabled = true)
        assertTrue(AirPlayPersistence.loadLowDelayH264Sps(context, ts7 = false))
    }

    @Test fun fpsDefaultsToSixtyOnlyForTs7AndSavedValueWins() {
        assertEquals(60, AirPlayPersistence.loadFps(context, ts7 = true))
        assertEquals(30, AirPlayPersistence.loadFps(context, ts7 = false))
        assertEquals(60, AirPlayDisplaySettings.sanitizeFps(AirPlayPersistence.loadFps(context, ts7 = true)))

        AirPlayPersistence.saveFps(context, 45)
        assertEquals(45, AirPlayPersistence.loadFps(context, ts7 = true))
        assertEquals(45, AirPlayPersistence.loadFps(context, ts7 = false))
    }

    @Test fun h264FrameEndMarkerIsFixedToAppendOnTs7AndSavedValueWinsElsewhere() {
        assertEquals(H264FrameEndMarkerMode.APPEND, AirPlayPersistence.loadH264FrameEndMarker(context, ts7 = true))
        assertEquals(H264FrameEndMarkerMode.OFF, AirPlayPersistence.loadH264FrameEndMarker(context, ts7 = false))

        AirPlayPersistence.saveH264FrameEndMarker(context, H264FrameEndMarkerMode.OFF)
        assertEquals(H264FrameEndMarkerMode.APPEND, AirPlayPersistence.loadH264FrameEndMarker(context, ts7 = true))
        assertEquals(H264FrameEndMarkerMode.OFF, AirPlayPersistence.loadH264FrameEndMarker(context, ts7 = false))
        AirPlayPersistence.saveH264FrameEndMarker(context, H264FrameEndMarkerMode.SEPARATE)
        assertEquals(H264FrameEndMarkerMode.APPEND, AirPlayPersistence.loadH264FrameEndMarker(context, ts7 = true))
        assertEquals(H264FrameEndMarkerMode.SEPARATE, AirPlayPersistence.loadH264FrameEndMarker(context, ts7 = false))
    }

    @Test fun directVideoOutputIsFixedOnForTs7AndSavedValueWinsElsewhere() {
        assertTrue(AirPlayPersistence.loadDirectVideoOutput(context, ts7 = true))
        assertFalse(AirPlayPersistence.loadDirectVideoOutput(context, ts7 = false))

        AirPlayPersistence.saveDirectVideoOutput(context, enabled = false)
        assertTrue(AirPlayPersistence.loadDirectVideoOutput(context, ts7 = true))
        assertFalse(AirPlayPersistence.loadDirectVideoOutput(context, ts7 = false))
        AirPlayPersistence.saveDirectVideoOutput(context, enabled = true)
        assertTrue(AirPlayPersistence.loadDirectVideoOutput(context, ts7 = false))
    }

    @Test fun hiddenVideoAndAudioSettingsUseTheTs7ProfileDespiteConflictingSavedValues() {
        AirPlayPersistence.saveHevcEnabled(context, true)
        AirPlayPersistence.saveHevcSoftwareDecoderEnabled(context, true)
        AirPlayPersistence.saveAdvancedAudioChannelMapping(context, true)
        AirPlayPersistence.saveAudioFocusEnabled(context, true)
        AirPlayPersistence.saveAudioFocusAutoYield(context, false)
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(context, true)
        AirPlayPersistence.saveMainBufferedAudio(context, true)
        AirPlayPersistence.saveSpeechWithout8And16Khz(context, true)
        AirPlayPersistence.saveCarBluetoothAudio(context, true)
        AirPlayPersistence.saveSmoothVideo(context, true)
        AirPlayPersistence.saveMtkDecoderTuning(context, true)
        AirPlayPersistence.saveLowLatencyDecoder(context, true)
        AirPlayPersistence.saveCallEchoCancellation(context, true)
        AirPlayPersistence.saveCallVoiceFilter(context, true)
        AirPlayPersistence.saveHarmanFullscreen(context, true)

        assertFalse(AirPlayPersistence.loadHevcEnabled(context, ts7 = true))
        assertFalse(AirPlayPersistence.loadHevcSoftwareDecoderEnabled(context, ts7 = true))
        assertFalse(AirPlayPersistence.loadAdvancedAudioChannelMapping(context, ts7 = true))
        assertFalse(AirPlayPersistence.loadAudioFocusEnabled(context, ts7 = true))
        assertTrue(AirPlayPersistence.loadAudioFocusAutoYield(context, ts7 = true))
        assertFalse(AirPlayPersistence.loadDisableMediaKeyAudioFocus(context, ts7 = true))
        assertFalse(AirPlayPersistence.loadMainBufferedAudio(context, ts7 = true))
        assertFalse(AirPlayPersistence.loadSpeechWithout8And16Khz(context, ts7 = true))
        assertFalse(AirPlayPersistence.loadCarBluetoothAudio(context, ts7 = true))
        assertFalse(AirPlayPersistence.loadSmoothVideo(context, ts7 = true))
        assertFalse(AirPlayPersistence.loadMtkDecoderTuning(context, ts7 = true))
        assertFalse(AirPlayPersistence.loadLowLatencyDecoder(context, ts7 = true))
        assertFalse(AirPlayPersistence.loadCallEchoCancellation(context, ts7 = true))
        assertFalse(AirPlayPersistence.loadCallVoiceFilter(context, ts7 = true))
        assertFalse(AirPlayPersistence.loadHarmanFullscreen(context, ts7 = true))

        assertTrue(AirPlayPersistence.loadHevcEnabled(context, ts7 = false))
        assertTrue(AirPlayPersistence.loadHevcSoftwareDecoderEnabled(context, ts7 = false))
        assertTrue(AirPlayPersistence.loadAdvancedAudioChannelMapping(context, ts7 = false))
        assertTrue(AirPlayPersistence.loadAudioFocusEnabled(context, ts7 = false))
        assertFalse(AirPlayPersistence.loadAudioFocusAutoYield(context, ts7 = false))
        assertTrue(AirPlayPersistence.loadDisableMediaKeyAudioFocus(context, ts7 = false))
        assertTrue(AirPlayPersistence.loadMainBufferedAudio(context, ts7 = false))
        assertTrue(AirPlayPersistence.loadSpeechWithout8And16Khz(context, ts7 = false))
        assertTrue(AirPlayPersistence.loadCarBluetoothAudio(context, ts7 = false))
        assertTrue(AirPlayPersistence.loadSmoothVideo(context, ts7 = false))
        assertTrue(AirPlayPersistence.loadMtkDecoderTuning(context, ts7 = false))
        assertTrue(AirPlayPersistence.loadLowLatencyDecoder(context, ts7 = false))
        assertTrue(AirPlayPersistence.loadCallEchoCancellation(context, ts7 = false))
        assertTrue(AirPlayPersistence.loadCallVoiceFilter(context, ts7 = false))
        assertTrue(AirPlayPersistence.loadHarmanFullscreen(context, ts7 = false))
    }

    @Test fun wirelessBufferDefaultsTo500OnlyForTs7AndNeverChangesWiredDefault() {
        assertEquals(500, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true, ts7 = true))
        assertEquals(300, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true, ts7 = false))
        assertEquals(300, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false, ts7 = true))

        prefs.edit().putInt("media_buffer_ms", 1000).putInt("wired_media_buffer_ms", 50).apply()
        assertEquals(1000, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true, ts7 = true))
        assertEquals(50, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false, ts7 = true))
    }

    @Test fun hiddenDisplayAndNavigationVolumeSettingsIgnoreSavedTs7Values() {
        SplitScreenSettings.setEnabled(context, true)
        CarPlayRotation.setEnabled(context, true)
        CarPlayRotation.setPicture(context, CarPlayRotation.Picture.SHARPER)
        context.getSharedPreferences("diplay_navigation_wheel", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", true).apply()

        assertFalse(SplitScreenSettings.enabled(context, ts7 = true))
        assertTrue(SplitScreenSettings.enabled(context, ts7 = false))
        assertFalse(CarPlayRotation.enabled(context, ts7 = true))
        assertTrue(CarPlayRotation.enabled(context, ts7 = false))
        assertEquals(CarPlayRotation.Picture.SMOOTHER, CarPlayRotation.picture(context, ts7 = true))
        assertEquals(CarPlayRotation.Picture.SHARPER, CarPlayRotation.picture(context, ts7 = false))
        assertFalse(NavigationWheelSettings.enabled(context, ts7 = true))
        assertTrue(NavigationWheelSettings.enabled(context, ts7 = false))
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

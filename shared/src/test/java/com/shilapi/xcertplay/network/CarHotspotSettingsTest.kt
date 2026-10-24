package com.shilapi.xcertplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarHotspotSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        context.getSharedPreferences("diplay_car_hotspot", 0).edit().clear().commit()
    }

    @Test fun automaticStartupDefaultRequiresTs7AndSupportedPlatform() {
        assertFalse(CarHotspotSettings.enabledByDefault(ts7Detected = false, appStartSupported = true))
        assertFalse(CarHotspotSettings.enabledByDefault(ts7Detected = true, appStartSupported = false))
        assertTrue(CarHotspotSettings.enabledByDefault(ts7Detected = true, appStartSupported = true))
    }

    @Test fun savedChoiceAlwaysOverridesTheHardwareDefault() {
        assertTrue(CarHotspotSettings.enabled(context, ts7Detected = true))
        CarHotspotSettings.setEnabled(context, false)
        assertFalse(CarHotspotSettings.enabled(context, ts7Detected = true))
        CarHotspotSettings.setEnabled(context, true)
        assertTrue(CarHotspotSettings.enabled(context, ts7Detected = false))
    }
}

package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], qualifiers = "en", manifest = Config.NONE)
class CarHotspotGenericSettingsTest {
    private lateinit var activity: DiPlayActivity

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        for (name in listOf("diplay_car_hotspot", "xcertplay_airplay", "diplay_byd_outputs"))
            app.getSharedPreferences(name, 0).edit().clear().commit()
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.MANUAL)
    }

    @Test fun nonBydCanOptInWithoutAdbAndDoesNotChangeSavedWifiDetails() {
        assertFalse(CarHotspotSetup.isBydHeadUnit(activity))
        AirPlayPersistence.saveManualHotspotSsid(activity, "Existing car AP")
        AirPlayPersistence.saveManualHotspotPassphrase(activity, "existing-password")
        val controls = render()
        val control = switches(controls).single()
        assertEquals(activity.getString(R.string.auto_car_hotspot_title), control.contentDescription)
        control.isChecked = true
        assertTrue(CarHotspotSettings.enabled(activity))
        assertEquals("Existing car AP", AirPlayPersistence.loadManualHotspotSsid(activity))
        assertEquals("existing-password", AirPlayPersistence.loadManualHotspotPassphrase(activity))
        assertFalse(com.shilapi.xcertplay.hud.BydOutputSettings.navigationAvailable(activity))
    }

    @Test fun otherHotspotModesNeverShowThisOption() {
        for (mode in listOf(WirelessHotspotMode.WIFI_P2P, WirelessHotspotMode.EXISTING_WIFI)) {
            AirPlayPersistence.saveWirelessHotspotMode(activity, mode)
            assertEquals(0, render().childCount)
        }
    }

    private fun render() = LinearLayout(activity).also {
        DiPlayActivity::class.java.getDeclaredMethod("bydAdbSettings", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, it)
    }

    private fun switches(view: View): List<Switch> = buildList {
        if (view is Switch) add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(switches(view.getChildAt(i)))
    }
}

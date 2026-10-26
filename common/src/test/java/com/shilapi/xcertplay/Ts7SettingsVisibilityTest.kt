package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.SafeAreaRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class Ts7SettingsVisibilityTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun ts7KeepsTheFloatingMapSectionsAndOmitsBydOnlySections() {
        val navigation = SettingsInformationArchitecture.visibleSections(SettingsCategory.NAVIGATION, ts7 = true)
        val advanced = SettingsInformationArchitecture.visibleSections(SettingsCategory.ADVANCED, ts7 = true)

        assertEquals(setOf(SettingsSection.LOCATION), navigation)
        assertTrue(SettingsSection.CLUSTER_MAP in advanced)
        assertFalse(SettingsSection.AMBIENT_LIGHTING in advanced)
        assertFalse(SettingsSection.BYD_NAVIGATION in SettingsInformationArchitecture.visibleSections(
            SettingsCategory.NAVIGATION, ts7 = true,
        ))
        assertTrue(SettingsCategory.VEHICLE in SettingsInformationArchitecture.visibleCategories(ts7 = true))
    }

    @Test fun aCategoryWithoutVisibleSectionsIsOmitted() {
        val categories = SettingsInformationArchitecture.visibleCategories(
            ts7 = false,
            categorySections = mapOf(SettingsCategory.VEHICLE to emptySet()),
        )

        assertFalse(SettingsCategory.VEHICLE in categories)
        assertTrue(SettingsCategory.OVERVIEW in categories)
        assertTrue(SettingsCategory.ABOUT in categories)
    }

    @Test fun ts7IgnoresSavedInstrumentAndMapEmbedValuesButKeepsTheFloatingCard() {
        AirPlayPersistence.saveClusterMapEnabled(context, true)
        AirPlayPersistence.saveAdbClusterEnabled(context, true)
        assertFalse(AirPlayPersistence.loadAdbClusterEnabled(context, ts7 = true))
        assertTrue(AirPlayPersistence.loadAdbClusterEnabled(context, ts7 = false))
        AirPlayPersistence.saveLegacyClusterEnabled(context, true)
        assertFalse(AirPlayPersistence.loadLegacyClusterEnabled(context, ts7 = true))
        assertTrue(AirPlayPersistence.loadLegacyClusterEnabled(context, ts7 = false))
        AirPlayPersistence.saveCenterMapOverlay(context, true)
        AirPlayPersistence.saveCenterMapAutoHide(context, false)
        AirPlayPersistence.saveCenterMapFollowsDashboard(context, true)
        AirPlayPersistence.saveLauncherMapSharing(context, true)
        AirPlayPersistence.saveClusterSpeedometerEnabled(context, true)
        AirPlayPersistence.saveClusterMapScalePercent(context, 125)
        AirPlayPersistence.saveClusterContent(context, CarPlayClusterDisplay.Content.INSTRUMENTS)
        AirPlayPersistence.saveClusterSmallWindowMode(context, 2)
        AirPlayPersistence.saveClusterSafeAreaRect(context, SafeAreaRect(300, 100, 1500, 620))

        assertTrue(AirPlayPersistence.loadClusterMapEnabled(context))
        assertTrue(AirPlayPersistence.loadCenterMapOverlay(context))
        assertFalse(AirPlayPersistence.loadCenterMapAutoHide(context))
        assertFalse(AirPlayPersistence.loadAdbClusterEnabled(context, ts7 = true))
        assertFalse(AirPlayPersistence.loadLegacyClusterEnabled(context, ts7 = true))
        assertFalse(AirPlayPersistence.loadDiLink4ClusterRouteEnabled(context, ts7 = true))
        assertFalse(AirPlayPersistence.loadCenterMapFollowsDashboard(context, ts7 = true))
        assertFalse(AirPlayPersistence.loadLauncherMapSharing(context, ts7 = true))
        assertFalse(AirPlayPersistence.loadClusterSpeedometerEnabled(context, ts7 = true))
        assertEquals(CarPlayClusterDisplay.STREAM_SCALE_PERCENT,
            AirPlayPersistence.loadClusterMapScalePercent(context, ts7 = true))
        assertEquals(CarPlayClusterDisplay.Content.MAP, AirPlayPersistence.loadClusterContent(context, ts7 = true))
        assertEquals(0, AirPlayPersistence.loadClusterSmallWindowMode(context, ts7 = true))
        assertNull(AirPlayPersistence.loadClusterSafeAreaRect(context, ts7 = true))

        assertTrue(AirPlayPersistence.loadCenterMapFollowsDashboard(context, ts7 = false))
        assertTrue(AirPlayPersistence.loadLauncherMapSharing(context, ts7 = false))
        assertTrue(AirPlayPersistence.loadClusterSpeedometerEnabled(context, ts7 = false))
        assertEquals(125, AirPlayPersistence.loadClusterMapScalePercent(context, ts7 = false))
        assertEquals(CarPlayClusterDisplay.Content.INSTRUMENTS,
            AirPlayPersistence.loadClusterContent(context, ts7 = false))
        assertEquals(2, AirPlayPersistence.loadClusterSmallWindowMode(context, ts7 = false))
        assertEquals(SafeAreaRect(300, 100, 1500, 620),
            AirPlayPersistence.loadClusterSafeAreaRect(context, ts7 = false))
    }

    @Test fun ts7IgnoresSavedBluetoothDelayFromTheHiddenVehiclePanel() {
        AirPlayPersistence.saveBtSuspendDuringCarplay(context, true)
        AirPlayPersistence.saveBtSuspendDelaySeconds(context, 30)

        assertFalse(AirPlayPersistence.loadBtSuspendDuringCarplay(context, ts7 = true))
        assertEquals(10, AirPlayPersistence.loadBtSuspendDelaySeconds(context, ts7 = true))
        assertTrue(AirPlayPersistence.loadBtSuspendDuringCarplay(context, ts7 = false))
        assertEquals(30, AirPlayPersistence.loadBtSuspendDelaySeconds(context, ts7 = false))
    }
}

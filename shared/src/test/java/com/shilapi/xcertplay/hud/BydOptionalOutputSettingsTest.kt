package com.shilapi.xcertplay.hud

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BydOptionalOutputSettingsTest {
    @Test fun optionalOutputsDefaultOffAndKeepExplicitPreviousSelections() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("diplay_byd_outputs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertFalse(BydOutputSettings.hudSong(app))
        assertEquals(BydOemClusterHold.OFF, BydOutputSettings.oemClusterHold(app))
        prefs.edit().putBoolean("oem_cluster_freeze", true).commit()
        assertEquals(BydOemClusterHold.PACKAGE, BydOutputSettings.oemClusterHold(app))
        BydOutputSettings.setOemClusterHold(app, BydOemClusterHold.COMPONENT)
        assertEquals(BydOemClusterHold.COMPONENT, BydOutputSettings.oemClusterHold(app))
    }

    @Test fun ts7TreatsSavedBydWorkSettingsAsOffButKeepsGenericPhoneAudioRouting() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("diplay_byd_outputs", Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putBoolean("navigation_enabled", true)
            .putBoolean("cluster_stream_pause", true)
            .putBoolean("battery_to_iphone", true)
            .putBoolean("wheel_speed_to_iphone", true)
            .putBoolean("video_while_parked", true)
            .putBoolean("cluster_song", true)
            .putBoolean("cluster_song_on_change", true)
            .putBoolean("hud_song", true)
            .putBoolean("carplay_calls", true)
            .putBoolean("carplay_call_controls_experimental", true)
            .putBoolean("fce_cluster_guidance_experimental", true)
            .putBoolean("legacy_vehicle_probe", true)
            .putString("oem_cluster_hold", BydOemClusterHold.COMPONENT.name)
            .putBoolean("phone_audio_route_experimental", true)
            .commit()

        assertFalse(BydOutputSettings.enabled(app, ts7 = true))
        assertFalse(BydOutputSettings.clusterStreamPause(app, ts7 = true))
        assertFalse(BydOutputSettings.batteryToIphone(app, ts7 = true))
        assertFalse(BydOutputSettings.wheelSpeedToIphone(app, ts7 = true))
        assertFalse(BydOutputSettings.videoWhileParked(app, ts7 = true))
        assertFalse(BydOutputSettings.clusterSong(app, ts7 = true))
        assertFalse(BydOutputSettings.clusterSongOnChange(app, ts7 = true))
        assertFalse(BydOutputSettings.hudSong(app, ts7 = true))
        assertFalse(BydOutputSettings.carPlayCalls(app, ts7 = true))
        assertFalse(BydOutputSettings.carPlayCallControls(app, ts7 = true))
        assertFalse(BydOutputSettings.fceCluster(app, ts7 = true))
        assertFalse(BydOutputSettings.legacyVehicleProbe(app, ts7 = true))
        assertEquals(BydOemClusterHold.OFF, BydOutputSettings.oemClusterHold(app, ts7 = true))
        assertFalse(BydOutputSettings.shouldStartBydNavigationOutputs(ts7 = true))
        assertTrue(BydOutputSettings.phoneAudioRoute(app))

        assertTrue(BydOutputSettings.enabled(app, ts7 = false))
        assertTrue(BydOutputSettings.clusterSong(app, ts7 = false))
        assertTrue(BydOutputSettings.carPlayCalls(app, ts7 = false))
        assertTrue(BydOutputSettings.shouldStartBydNavigationOutputs(ts7 = false))
    }

    @Test fun installedStockReceiverDoesNotEnableUnverifiedDilink4Hud() {
        val app = RuntimeEnvironment.getApplication()
        val knownApp = object : ContextWrapper(app) {
            override fun getPackageName(): String = "com.shihab.diplay"
        }
        ShadowBuild.setFingerprint("BYD/DiLink4:10/unverified")
        val info = PackageInfo().apply {
            packageName = "com.byd.clusterdebug"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.byd.clusterdebug"
                flags = ApplicationInfo.FLAG_SYSTEM
            }
        }
        shadowOf(app.packageManager).installPackage(info)
        assertFalse(BydStandaloneHudOutput.available(knownApp))
        assertTrue(BydStandaloneHudOutput.diagnostics(knownApp).contains("standaloneHudAvailable=false"))
    }

}

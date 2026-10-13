package com.shilapi.xcertplay

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Test

/** Stock API27 checks observation and denial, not privileged tethering or TS7 CarPlay. */
class CarHotspotRecoveryRuntimeTest {
    @Test fun backgroundWakeWaitsForAirplaneExitAndReportsPermissionRequired() {
        assertEquals(27, Build.VERSION.SDK_INT)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("diplay_car_hotspot", 0)
        val originalWireless = AirPlayPersistence.loadWirelessEnabled(context)
        val originalMode = AirPlayPersistence.loadWirelessHotspotMode(context)
        val originalOptIn = CarHotspotSettings.enabled(context)
        val originalAirplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
        fun shell(command: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .use { it.readBytes() }
        }
        fun airplane(on: Boolean) {
            shell("settings put global airplane_mode_on ${if (on) 1 else 0}")
            shell("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $on")
        }
        try {
            assertFalse(Settings.System.canWrite(context))
            airplane(true)
            AirPlayPersistence.saveWirelessEnabled(context, true)
            AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
            CarHotspotSettings.setEnabled(context, true)
            shell("input keyevent 3") // Home; no DiPlay Activity resume is used for wake detection.
            CarHotspotRecoveryService.sync(context)
            SystemClock.sleep(500)
            shell("input keyevent 223")
            SystemClock.sleep(500)
            prefs.edit().remove("last_recovery").commit()
            shell("input keyevent 224")
            SystemClock.sleep(1_500)
            assertNull(prefs.getString("last_recovery", null))
            airplane(false)
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (prefs.getString("last_recovery", null) == null && SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(100)
            assertEquals("PERMISSION_REQUIRED", prefs.getString("last_recovery", null))
        } finally {
            context.stopService(Intent(context, CarHotspotRecoveryService::class.java))
            CarHotspotSettings.setEnabled(context, originalOptIn)
            AirPlayPersistence.saveWirelessEnabled(context, originalWireless)
            AirPlayPersistence.saveWirelessHotspotMode(context, originalMode)
            airplane(originalAirplane != 0)
            shell("input keyevent 224")
        }
    }
}

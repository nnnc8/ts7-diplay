package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class Ts7BydProfileTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun allOptionalVendorOutputsDefaultOff() {
        assertFalse(BydOutputSettings.enabled(context))
        assertFalse(BydOutputSettings.clusterStreamPause(context))
        assertFalse(BydOutputSettings.batteryToIphone(context))
        assertFalse(BydOutputSettings.available(context))
    }

    @Test fun savedVendorPreferencesCannotStartWorkersOrAdbInPublicProfile() {
        BydOutputSettings.setEnabled(context, true)
        BydOutputSettings.setClusterStreamPause(context, true)
        BydOutputSettings.setBatteryToIphone(context, true)
        val before = vendorThreads()
        var callbacks = 0
        val control: (Boolean) -> Unit = { callbacks++ }

        BydNavigationOutputs.onAppOpened(context)
        BydNavigationOutputs.start(context)
        BydNavigationOutputs.batteryStatus(context)
        BydNavigationOutputs.setClusterStreamControl(control)
        BydNavigationOutputs.setClusterMapShown(false)
        BydNavigationOutputs.onFrame(Iap2Frame(BydHudRouteState.ROUTE_GUIDANCE_UPDATE, ByteArray(0)))
        BydNavigationOutputs.endNow()
        BydNavigationOutputs.clearClusterStreamControl(control)

        assertEquals(before, vendorThreads())
        assertEquals(0, callbacks)
        assertNull(BydClusterMapPause.streamControl)
        assertFalse(File(context.noBackupFilesDir, "adb").exists())
    }

    @Test fun manualAdbSettingsCheckCannotCreateAnIdentityOrConnect() {
        assertEquals(BydAdbAccess.State.ADB_OFF, BydAdbAccess.check(context, true).state)
        assertEquals(BydAdbAccess.State.ADB_OFF, BydAdbAccess.check(context, false).state)
        assertFalse(File(context.noBackupFilesDir, "adb").exists())
    }

    private fun vendorThreads(): Set<Thread> = Thread.getAllStackTraces().keys.filter {
        it.name.startsWith("diplay-hud") || it.name.startsWith("diplay-cluster") ||
            it.name.startsWith("diplay-standalone") || it.name.startsWith("diplay-battery")
    }.toSet()
}

package com.shilapi.xcertplay

import android.content.Intent
import android.os.Looper
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SessionDiagnosticCoverageTest {
    @Test fun openSettingsMenuStillWritesControllerDiagnosticsToDisk() {
        val controller = Robolectric.buildActivity(CarPlayHostActivity::class.java)
        val activity = controller.get()
        val type = CarPlayHostActivity::class.java
        type.getDeclaredMethod("initializeSessionLog").apply { isAccessible = true }.invoke(activity)
        type.getDeclaredField("menuOpen").apply { isAccessible = true }.setBoolean(activity, true)
        val generation = type.getDeclaredField("restartGeneration").apply { isAccessible = true }.getInt(activity)
        val listener = type.getDeclaredMethod("createSessionListener", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, generation) as AirPlaySessionListener
        val message = "airplay TEARDOWN types=102 activeBefore=102 bodyBytes=128"
        listener.onDebugLog(message)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(File(activity.filesDir, "logs/diplay.log").readText().contains(message))
        (type.getDeclaredField("sessionLog").apply { isAccessible = true }.get(activity) as SessionLogFile).close()
    }

    @Test fun bluetoothRoleBroadcastKeepsStateAndNoDeviceIdentifier() {
        val context = RuntimeEnvironment.getApplication()
        val lines = mutableListOf<String>()
        val monitor = CarBluetoothProfileDiagnostics(context, { null }, lines::add)
        try {
            monitor.start()
            context.sendBroadcast(Intent("android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED")
                .putExtra(android.bluetooth.BluetoothProfile.EXTRA_STATE, 2))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(lines.any { it.contains("change=hfpClient state=2 carPlayPhone=unknown atMs=") })
            assertTrue(lines.all { DiagnosticRedactor.redact(it) == it })
        } finally { monitor.stop() }
    }
}

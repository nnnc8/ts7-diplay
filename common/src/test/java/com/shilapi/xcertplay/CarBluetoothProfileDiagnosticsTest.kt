package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CarBluetoothProfileDiagnosticsTest {
    @Test fun usesTheHeadUnitRolesAndExportsOnlyState() {
        assertEquals("a2dpSink", CarBluetoothProfileDiagnostics.profileFor("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"))
        assertEquals("hfpClient", CarBluetoothProfileDiagnostics.profileFor("android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED"))
        assertNull(CarBluetoothProfileDiagnostics.profileFor("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED"))
        val line = CarBluetoothProfileDiagnostics.transition("hfpClient", 2, true, 1000)
        assertEquals("Bluetooth: car profiles change=hfpClient state=2 carPlayPhone=true atMs=1000", line)
        assertEquals(line, DiagnosticRedactor.redact(line))
    }
}

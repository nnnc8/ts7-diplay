package com.shilapi.xcertplay.airplay

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AirPlaySessionTouchTraceTest {
    @Before fun resetLatencyProbe() = com.shilapi.xcertplay.media.TouchLatencyProbe.resetForTest()
    @After fun clearLatencyProbe() = com.shilapi.xcertplay.media.TouchLatencyProbe.resetForTest()

    @Test fun touchReportCanSkipHexTraceWithoutSkippingTheHidWrite() {
        for (traceTouchReports in listOf(true, false)) {
            val messages = mutableListOf<String>()
            val session = session(traceTouchReports, messages)
            val event = EventSocket()
            setField(session, "eventSocket", event)
            setField(session, "eventCipher", ControlCipher(ByteArray(32), ByteArray(32)))
            try {
                assertTrue(session.sendTouch(listOf(AirPlayContact(id = 0, x = 0.5, y = 0.5, down = true))))
                assertTrue(event.bytes.size() > 0)
                assertEquals(traceTouchReports, messages.any { it.startsWith("TRACE airplay event tx") })
            } finally {
                session.close()
            }
        }
    }

    private fun session(traceTouchReports: Boolean, messages: MutableList<String>) = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = object : AirPlaySessionListener {
            override fun onDebugLog(message: String) { messages += message }
        },
        media = object : AirPlayMediaHandler {},
        traceTouchHidReports = traceTouchReports,
    )

    private fun setField(instance: Any, name: String, value: Any) {
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
    }

    private class EventSocket : Socket() {
        val bytes = ByteArrayOutputStream()
        override fun getOutputStream(): OutputStream = bytes
    }
}

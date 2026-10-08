package com.shilapi.xcertplay.orchestration

import android.os.Looper
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.UsbDeviceId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class Ts7AuthenticationStatusTest {
    @Test fun authenticationBlockIsNotAnAutomaticallyRetryableGenericFailure() {
        val status = CarPlayStatus.Failed("AUTH_BLOCKED").publicDiagnosticStatus()
        assertEquals(CarPlayStatus.AuthBlocked, status)
        assertFalse(status is CarPlayStatus.Failed)
    }

    @Test fun arbitraryFailureDetailsAreReplacedWithFixedCodes() {
        assertEquals(CarPlayStatus.Failed("CONNECTION_FAILED"), CarPlayStatus.Failed("SENTINEL").publicDiagnosticStatus())
        assertEquals(CarPlayStatus.Failed("WIFI_RESET_REQUIRED", true), CarPlayStatus.Failed("WIFI_RESET_REQUIRED", true).publicDiagnosticStatus())
    }

    @Test fun radioStatusExposesBandChannelAndBackendButNotIdentifiers() {
        val safe = CarPlayStatus.HotspotReady("SENTINEL", "2.4 GHz", 6, "C0:A6:00:29:58:0A", "192.168.31.71", "test")
            .publicDiagnosticStatus() as CarPlayStatus.HotspotReady
        assertEquals("[redacted]", safe.ssid)
        assertEquals("[redacted]", safe.bssid)
        assertEquals("[redacted]", safe.address)
        assertEquals("2.4 GHz", safe.band)
        assertEquals(6, safe.channel)
        assertEquals("test", safe.backend)
    }

    @Test fun missingLocalProviderStopsBeforePhoneOrHotspotBringupWithoutPolling() {
        val statuses = mutableListOf<CarPlayStatus>()
        val controller = controller(MfiTarget.LOCAL, statuses)
        try {
            controller.start()
            awaitStatus(statuses, CarPlayStatus.AuthBlocked)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertEquals(1, statuses.count { it == CarPlayStatus.DiscoveringMfi })
            assertEquals(listOf(CarPlayStatus.DiscoveringMfi, CarPlayStatus.AuthBlocked), statuses)
            assertFalse(File(RuntimeEnvironment.getApplication().noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY).exists())
        } finally {
            controller.close()
            assertTrue(controller.awaitClosed(3_000))
        }
    }

    @Test fun anEmptyLocalDirectoryDoesNotOverrideExplicitUsbProviderSelection() {
        val directory = File(RuntimeEnvironment.getApplication().noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)
        assertTrue(directory.mkdirs())
        val statuses = mutableListOf<CarPlayStatus>()
        val controller = controller(MfiTarget.USB_CH341, statuses)
        try {
            controller.start()
            awaitStatus(statuses, CarPlayStatus.WaitingForMfi)
            assertFalse(statuses.contains(CarPlayStatus.AuthBlocked))
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally {
            controller.close()
            assertTrue(controller.awaitClosed(3_000))
        }
    }

    private fun controller(target: MfiTarget, statuses: MutableList<CarPlayStatus>): CarPlayController = CarPlayController(
        RuntimeEnvironment.getApplication(),
        CarPlayRuntimeConfig(
            mfiTarget = target,
            ch341Devices = if (target == MfiTarget.USB_CH341) listOf(UsbDeviceId(0x1111, 0x2222)) else emptyList(),
            transport = CarPlayTransport.WIRELESS,
            identification = Iap2IdentificationConfig(
                name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3,
            ),
        ),
        AirPlayConfig("test", "test", "test", "test", AirPlayDisplayConfig(800, 480)),
        // Deliberately non-signing dummy bytes; the blocked path must never create an identity.
        AirPlayIdentity(ByteArray(32), ByteArray(32), "synthetic-test"),
        PairingStore(), object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
        reportStatus = statuses::add,
    )

    private fun awaitStatus(statuses: List<CarPlayStatus>, expected: CarPlayStatus) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!statuses.contains(expected) && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("Expected $expected but received $statuses", statuses.contains(expected))
    }
}

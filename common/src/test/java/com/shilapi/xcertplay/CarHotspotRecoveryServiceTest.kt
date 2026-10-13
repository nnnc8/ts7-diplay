package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.network.CarHotspotWakeRecovery
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], shadows = [CarHotspotRecoveryServiceTest.Recovery::class])
class CarHotspotRecoveryServiceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var service: CarHotspotRecoveryService
    private val power get() = shadowOf(app.getSystemService(Context.POWER_SERVICE) as PowerManager)

    @Before fun setUp() {
        CarPlayBackgroundSession.clear()
        for (name in listOf("diplay_car_hotspot", "xcertplay_airplay")) app.getSharedPreferences(name, 0).edit().clear().commit()
        AirPlayPersistence.saveWirelessEnabled(app, true)
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.MANUAL)
        CarHotspotSettings.setEnabled(app, true)
        Recovery.calls.set(0)
        Recovery.entered = CountDownLatch(1)
        Recovery.release = CountDownLatch(1)
        power.setIsInteractive(true)
        service = Robolectric.buildService(CarHotspotRecoveryService::class.java).create().get()
        service.onStartCommand(Intent(), 0, 1)
        assertTrue(Recovery.entered.await(3, TimeUnit.SECONDS))
    }

    @After fun tearDown() {
        Recovery.release.countDown()
        service.onDestroy()
        CarHotspotSettings.setEnabled(app, false)
        CarPlayBackgroundSession.clear()
    }

    @Test fun repeatedScreenOnAndActivitySyncDoNotDuplicateAWorker() {
        repeat(10) {
            send(Intent.ACTION_SCREEN_ON)
            service.onStartCommand(Intent(), 0, 2 + it)
        }
        assertEquals(1, Recovery.calls.get())
    }

    @Test fun backgroundObserverReceivesWakeWithoutAnActivityResume() {
        power.setIsInteractive(false)
        send(Intent.ACTION_SCREEN_OFF)
        Recovery.entered = CountDownLatch(1)
        power.setIsInteractive(true)
        send(Intent.ACTION_SCREEN_ON)
        assertTrue(Recovery.entered.await(3, TimeUnit.SECONDS))
        assertEquals(2, Recovery.calls.get())
    }

    @Test fun airplaneChangesNeverCreateIndependentWorkers() {
        repeat(10) { send(Intent.ACTION_AIRPLANE_MODE_CHANGED) }
        assertEquals(1, Recovery.calls.get())
    }

    @Test fun eligibilityDoesNotEnableOtherWirelessModesOrBydFeatures() {
        assertTrue(CarHotspotRecoveryService.eligible(app))
        for (mode in listOf(WirelessHotspotMode.WIFI_P2P, WirelessHotspotMode.EXISTING_WIFI)) {
            AirPlayPersistence.saveWirelessHotspotMode(app, mode)
            assertFalse(CarHotspotRecoveryService.eligible(app))
            assertFalse(CarHotspotSettings.visible(mode))
        }
        assertFalse(CarHotspotSetup.isBydHeadUnit(app))
        assertFalse(com.shilapi.xcertplay.hud.BydOutputSettings.navigationAvailable(app))
        assertFalse(CarHotspotSettings.visible(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT))
    }

    private fun send(action: String) {
        app.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Implements(CarHotspotWakeRecovery::class, isInAndroidSdk = false)
    class Recovery {
        @Implementation fun recover(cancelled: () -> Boolean,
            state: () -> CarHotspotWakeRecovery.State,
            enable: (() -> Boolean, Long) -> CarHotspotTethering.Result,
            now: () -> Long, pause: (Long) -> Unit): CarHotspotWakeRecovery.Outcome {
            calls.incrementAndGet()
            entered.countDown()
            try {
                while (!cancelled() && !release.await(10, TimeUnit.MILLISECONDS)) { }
            } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            return CarHotspotWakeRecovery.Outcome.CANCELLED
        }
        companion object {
            val calls = AtomicInteger()
            lateinit var entered: CountDownLatch
            lateinit var release: CountDownLatch
        }
    }
}

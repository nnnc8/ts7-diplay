package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.network.CarHotspotWakeRecovery
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Future
import java.time.Duration
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
import org.robolectric.shadows.ShadowSystemClock

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
        Recovery.outcome = CarHotspotWakeRecovery.Outcome.CANCELLED
        Settings.Global.putInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
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

    @Test fun airplaneExitAfterTimeoutStartsANewPassButScreenOnAndSyncDoNot() {
        Settings.Global.putInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 1)
        complete(CarHotspotWakeRecovery.Outcome.HOTSPOT_START_FAILED)
        repeat(5) { send(Intent.ACTION_SCREEN_ON); service.onStartCommand(Intent(), 0, it + 2) }
        send(Intent.ACTION_AIRPLANE_MODE_CHANGED) // Still on: not an exit.
        assertEquals(1, Recovery.calls.get())
        Settings.Global.putInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
        send(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        assertTrue(Recovery.entered.await(3, TimeUnit.SECONDS))
        assertEquals(2, Recovery.calls.get())
        repeat(5) { send(Intent.ACTION_AIRPLANE_MODE_CHANGED) }
        assertEquals(2, Recovery.calls.get())
    }

    @Test fun apDropRequiresCooldownTetheredModeNoAirplaneAndNoLiveSession() {
        complete(CarHotspotWakeRecovery.Outcome.ALREADY_ENABLED)
        fun drop(mode: Int = 1) = send(Intent("android.net.wifi.WIFI_AP_STATE_CHANGED")
            .putExtra("wifi_state", 11).putExtra("wifi_ap_mode", mode))
        drop()
        assertEquals(1, Recovery.calls.get())
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        drop(2)
        Settings.Global.putInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 1)
        drop()
        Settings.Global.putInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
        CarPlayBackgroundSession.active = true
        drop()
        assertEquals(1, Recovery.calls.get())
        CarPlayBackgroundSession.active = false
        drop()
        assertTrue(Recovery.entered.await(3, TimeUnit.SECONDS))
        assertEquals(2, Recovery.calls.get())
        drop()
        assertEquals(2, Recovery.calls.get())
    }

    @Test fun malformedConfiguredPasswordAndPendingSetupAreIneligibleButValidAndDefaultSettingsWork() {
        assertTrue(CarHotspotRecoveryService.eligible(app))
        AirPlayPersistence.saveManualHotspotSsid(app, "Existing TS7")
        for (length in 1..7) {
            AirPlayPersistence.saveManualHotspotPassphrase(app, "a".repeat(length))
            assertFalse(CarHotspotRecoveryService.eligible(app))
        }
        AirPlayPersistence.saveManualHotspotPassphrase(app, "validpassword")
        assertTrue(CarHotspotRecoveryService.eligible(app))
        AirPlayPersistence.saveManualHotspotPassphrase(app, "") // An open saved AP is valid.
        assertTrue(CarHotspotRecoveryService.eligible(app))
        CarHotspotSettings.setSetupPending(app, true)
        assertFalse(CarHotspotRecoveryService.eligible(app))
        CarHotspotSettings.setSetupPending(app, false)
        assertTrue(CarHotspotRecoveryService.eligible(app))
    }

    @Test fun diagnosticWritesDoNotRecheckEligibilityButOptOutStillStopsTheObserver() {
        CarHotspotSettings.setSetupPending(app, true)
        app.getSharedPreferences("diplay_car_hotspot", 0).edit().putString("last_recovery", "CANCELLED").apply()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shadowOf(service).isStoppedBySelf)
        CarHotspotSettings.setEnabled(app, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    private fun complete(outcome: CarHotspotWakeRecovery.Outcome) {
        Recovery.outcome = outcome
        Recovery.release.countDown()
        (CarHotspotRecoveryService::class.java.getDeclaredField("task").apply { isAccessible = true }
            .get(service) as Future<*>).get(3, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        Recovery.entered = CountDownLatch(1)
        Recovery.release = CountDownLatch(1)
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
        send(Intent(action))
    }

    private fun send(intent: Intent) {
        app.sendBroadcast(intent)
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
            return outcome
        }
        companion object {
            val calls = AtomicInteger()
            lateinit var entered: CountDownLatch
            lateinit var release: CountDownLatch
            @Volatile var outcome = CarHotspotWakeRecovery.Outcome.CANCELLED
        }
    }
}

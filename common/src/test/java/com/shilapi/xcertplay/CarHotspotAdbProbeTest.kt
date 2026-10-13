package com.shilapi.xcertplay

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
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
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE, shadows = [CarHotspotAdbProbeTest.Setup::class])
class CarHotspotAdbProbeTest {
    private lateinit var activity: DiPlayActivity
    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        for (name in listOf("diplay_car_hotspot", "xcertplay_airplay", "diplay_byd_outputs"))
            app.getSharedPreferences(name, 0).edit().clear().commit()
        Setup.calls.set(0)
        Setup.entered = CountDownLatch(1)
        Setup.release = CountDownLatch(1)
        Setup.grantEntered = CountDownLatch(1)
        Setup.grantRelease = CountDownLatch(1)
        Setup.access = LocalAdb.Access.NOT_APPROVED
        Setup.grantWorker = null
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.MANUAL)
    }

    @After fun tearDown() {
        Setup.release.countDown()
        Setup.grantRelease.countDown()
        Setup.grantWorker?.join(3_000)
        future()?.get(3, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        val getter = DiPlayActivity::class.java.getDeclaredMethod("getHotspotAdbChecks").apply { isAccessible = true }
        (getter.invoke(activity) as java.util.concurrent.ExecutorService).shutdownNow()
    }

    @Test fun repeatedRendersShareOneProbeAndUpdateOnlyCurrentControls() {
        val old = render()
        assertTrue(Setup.entered.await(3, TimeUnit.SECONDS))
        var current = old
        repeat(20) { current = render() }
        assertEquals(1, Setup.calls.get())
        completeProbe()
        assertEquals(View.GONE, old.visibility)
        assertEquals(View.VISIBLE, current.visibility)
        assertTrue(current.childCount > 0)
    }

    @Test fun authorizationAndVehicleOperationsSuppressChecksUntilControlsRefresh() {
        for (field in listOf("adbSwitchChangePending", "adbCheckInProgress",
            "vehicleProbeAuthorizationInProgress", "vehicleProbeInProgress")) {
            set(field, true)
            render()
            assertEquals(0, Setup.calls.get())
            set(field, false)
        }
        DiPlayActivity::class.java.getDeclaredMethod("updateAdbSwitches").apply { isAccessible = true }.invoke(activity)
        assertTrue(Setup.entered.await(3, TimeUnit.SECONDS))
        completeProbe()
        assertEquals(1, Setup.calls.get())
    }

    @Test fun authorizationInvalidatesAnEarlierProbeAndCompletionChecksAgain() {
        val controls = render()
        assertTrue(Setup.entered.await(3, TimeUnit.SECONDS))
        val save: () -> Unit = {}
        DiPlayActivity::class.java.getDeclaredMethod("requestAdbSwitchChange", List::class.java,
            kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
            .invoke(activity, emptyList<CarHotspotSetup.Permission>(), save)
        assertTrue(Setup.grantEntered.await(3, TimeUnit.SECONDS))
        completeProbe()
        assertEquals(View.GONE, controls.visibility)
        Setup.access = LocalAdb.Access.UNREACHABLE
        Setup.entered = CountDownLatch(1)
        Setup.grantRelease.countDown()
        Setup.grantWorker!!.join(3_000)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(Setup.entered.await(3, TimeUnit.SECONDS))
        completeProbe()
        assertEquals(2, Setup.calls.get())
        assertEquals(View.GONE, controls.visibility)
    }

    private fun render(): LinearLayout {
        val parent = LinearLayout(activity)
        DiPlayActivity::class.java.getDeclaredMethod("bydAdbSettings", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, parent)
        return parent.getChildAt(0) as LinearLayout
    }
    private fun set(name: String, value: Any) = DiPlayActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(activity, value)
    private fun future(): Future<*>? = DiPlayActivity::class.java.getDeclaredField("hotspotAdbCheck")
        .apply { isAccessible = true }.get(activity) as? Future<*>
    private fun completeProbe() {
        Setup.release.countDown()
        future()!!.get(3, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Implements(CarHotspotSetup::class, isInAndroidSdk = false)
    internal class Setup {
        @Implementation fun isBydHeadUnit(context: Context): Boolean = true
        @Implementation fun check(context: Context, adb: LocalAdb): LocalAdb.Access {
            val result = access
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            return result
        }
        @Implementation fun grant(context: Context, permissions: List<CarHotspotSetup.Permission>, adb: LocalAdb): LocalAdb.Access {
            grantWorker = Thread.currentThread()
            grantEntered.countDown()
            check(grantRelease.await(3, TimeUnit.SECONDS))
            return LocalAdb.Access.NOT_APPROVED
        }
        companion object {
            val calls = AtomicInteger()
            lateinit var entered: CountDownLatch
            lateinit var release: CountDownLatch
            lateinit var grantEntered: CountDownLatch
            lateinit var grantRelease: CountDownLatch
            @Volatile var grantWorker: Thread? = null
            @Volatile var access = LocalAdb.Access.NOT_APPROVED
        }
    }
}

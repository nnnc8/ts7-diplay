package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The TS7 is a Toyota: its car-button label and packaged icon follow, every other head unit stays BYD. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class Ts7OemBrandingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Before fun clearPreferences() {
        prefs.edit().clear().apply()
    }

    @Test fun ts7ReplacesMissingBlankAndBydLabelsWithToyota() {
        assertEquals("Toyota", AirPlayPersistence.loadOemLabel(context, ts7 = true))

        AirPlayPersistence.saveOemLabel(context, "")
        assertEquals("Toyota", AirPlayPersistence.loadOemLabel(context, ts7 = true))

        AirPlayPersistence.saveOemLabel(context, "   ")
        assertEquals("Toyota", AirPlayPersistence.loadOemLabel(context, ts7 = true))

        AirPlayPersistence.saveOemLabel(context, "BYD")
        assertEquals("Toyota", AirPlayPersistence.loadOemLabel(context, ts7 = true))
    }

    @Test fun ts7KeepsTheDriversOwnLabel() {
        AirPlayPersistence.saveOemLabel(context, "My Car")
        assertEquals("My Car", AirPlayPersistence.loadOemLabel(context, ts7 = true))
    }

    @Test fun otherHeadUnitsKeepTheBydBehaviour() {
        assertEquals("BYD", AirPlayPersistence.loadOemLabel(context, ts7 = false))

        AirPlayPersistence.saveOemLabel(context, "BYD")
        assertEquals("BYD", AirPlayPersistence.loadOemLabel(context, ts7 = false))

        AirPlayPersistence.saveOemLabel(context, "My Car")
        assertEquals("My Car", AirPlayPersistence.loadOemLabel(context, ts7 = false))

        AirPlayPersistence.saveOemLabel(context, "")
        assertEquals("BYD", AirPlayPersistence.loadOemLabel(context, ts7 = false))
    }

    @Test fun thePackagedCarButtonIconFollowsTheHeadUnit() {
        assertEquals(R.raw.ic_car_home_toyota, AirPlayPersistence.defaultAirPlayIconResource(ts7 = true))
        assertEquals(R.raw.ic_car_home, AirPlayPersistence.defaultAirPlayIconResource(ts7 = false))
    }

    @Test fun theDefaultLabelBeforeAnyLoadFollowsTheHeadUnit() {
        assertEquals("Toyota", AirPlayPersistence.defaultOemLabel(ts7 = true))
        assertEquals("BYD", AirPlayPersistence.defaultOemLabel(ts7 = false))
        assertEquals("BYD", AirPlayPersistence.DEFAULT_OEM_LABEL)
    }
}

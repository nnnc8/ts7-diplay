package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The TS7 keeps only a slow safety-net poll; every other head unit keeps the 2-second BYD poll. */
class Ts7ThemePollIntervalTest {
    @Test fun ts7PollsAtMostOnceAMinuteAndOthersKeepTheTwoSecondPoll() {
        assertEquals(60_000L, configurationPollIntervalMillis(ts7 = true))
        assertEquals(2_000L, configurationPollIntervalMillis(ts7 = false))
        assertTrue(configurationPollIntervalMillis(ts7 = true) > configurationPollIntervalMillis(ts7 = false))
    }

    @Test fun theScheduleNightWindowIsUnchangedByThePollInterval() {
        // The schedule mode ticks on its own minute boundary (CarPlayNightModeController),
        // so slowing the configuration poll must not move any night-window result.
        val schedule = CarPlayNightSchedule(startMinute = 18 * 60, endMinute = 6 * 60)
        for (minute in 0 until 24 * 60) {
            val expected = minute >= 18 * 60 || minute < 6 * 60
            assertEquals("minute=$minute", expected, schedule.isNight(minute))
        }
    }
}

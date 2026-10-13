package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.network.CarHotspotTethering.Result
import com.shilapi.xcertplay.network.CarHotspotWakeRecovery.Outcome
import com.shilapi.xcertplay.network.CarHotspotWakeRecovery.State
import org.junit.Assert.*
import org.junit.Test

class CarHotspotWakeRecoveryTest {
    private class Rig {
        var time = 0L
        var calls = 0
        var cancelled = false
        var state = State(true, true, false, true, false, false)
        val starts = mutableListOf<Long>()
        var tick: (Rig) -> Unit = {}
        var request: (Rig, () -> Boolean, Long) -> Result = { rig, _, _ ->
            rig.state = rig.state.copy(hotspotEnabled = true)
            Result.READY
        }
        fun run() = CarHotspotWakeRecovery.recover({ cancelled }, { state }, { stop, timeout ->
            calls++; starts += time
            assertTrue(timeout in 1..CarHotspotWakeRecovery.ATTEMPT_TIMEOUT_MILLIS)
            request(this, stop, timeout)
        }, { time }, { time += it; tick(this) })
    }

    @Test fun normalWakeSettlesBeforeRestoring() {
        val rig = Rig()
        assertEquals(Outcome.HOTSPOT_RESTORED, rig.run())
        assertEquals(listOf(3_000L), rig.starts)
    }

    @Test fun delayedAirplaneExitAndRadioRecoveryAreBothRequired() {
        val rig = Rig().apply {
            state = state.copy(airplaneMode = true, radioReady = false)
            tick = { it.state = it.state.copy(airplaneMode = it.time < 7_000, radioReady = it.time >= 9_000) }
        }
        assertEquals(Outcome.HOTSPOT_RESTORED, rig.run())
        assertEquals(listOf(12_000L), rig.starts)
    }

    @Test fun airplaneNeverExitsWithoutAnyStart() {
        val rig = Rig().apply { state = state.copy(airplaneMode = true) }
        assertEquals(Outcome.HOTSPOT_START_FAILED, rig.run())
        assertEquals(0, rig.calls)
        assertEquals(60_000L, rig.time)
    }

    @Test fun alreadyEnabledNeedsNoRequest() {
        val rig = Rig().apply { state = state.copy(hotspotEnabled = true) }
        assertEquals(Outcome.ALREADY_ENABLED, rig.run())
        assertEquals(0, rig.calls)
    }

    @Test fun permanentFailuresHaveFixedCodesAndDoNotRetry() {
        for ((result, expected) in listOf(Result.PERMISSION_REQUIRED to Outcome.PERMISSION_REQUIRED,
            Result.UNSUPPORTED to Outcome.HOTSPOT_UNSUPPORTED)) {
            val rig = Rig().apply { request = { _, _, _ -> result } }
            assertEquals(expected, rig.run())
            assertEquals(1, rig.calls)
        }
    }

    @Test fun failedStartupHasOnlyThreeAttempts() {
        val rig = Rig().apply { request = { _, _, _ -> Result.TIMED_OUT } }
        assertEquals(Outcome.HOTSPOT_START_FAILED, rig.run())
        assertEquals(listOf(3_000L, 8_000L, 13_000L), rig.starts)
    }

    @Test fun apiAcceptanceWithoutEnabledApIsNotSuccess() {
        val rig = Rig().apply { request = { _, _, _ -> Result.READY } }
        assertEquals(Outcome.HOTSPOT_START_FAILED, rig.run())
        assertEquals(3, rig.calls)
    }

    @Test fun establishedSessionDoesNotTouchHotspot() {
        val rig = Rig().apply { state = state.copy(sessionActive = true) }
        assertEquals(Outcome.SESSION_ACTIVE, rig.run())
        assertEquals(0, rig.calls)
    }

    @Test fun stalePreSleepSessionIsRecheckedAfterAirplaneExit() {
        val rig = Rig().apply {
            state = state.copy(airplaneMode = true, sessionActive = true)
            tick = { it.state = it.state.copy(airplaneMode = it.time < 3_000, sessionActive = it.time < 3_500) }
        }
        assertEquals(Outcome.HOTSPOT_RESTORED, rig.run())
        assertEquals(listOf(6_000L), rig.starts)
    }

    @Test fun sessionEstablishedDuringStartCancelsWithoutRetry() {
        val rig = Rig().apply { request = { it, stop, _ ->
            it.state = it.state.copy(sessionActive = true)
            assertTrue(stop())
            Result.CANCELLED
        } }
        assertEquals(Outcome.SESSION_ACTIVE, rig.run())
        assertEquals(1, rig.calls)
    }

    @Test fun sleepAndOptOutCancelBeforeAnyMutation() {
        for (sleep in listOf(true, false)) {
            val rig = Rig().apply { tick = {
                if (it.time >= 500) it.state = it.state.copy(interactive = !sleep, optedIn = sleep)
            } }
            assertEquals(Outcome.CANCELLED, rig.run())
            assertEquals(0, rig.calls)
        }
    }

    @Test fun sleepDuringRequestCancelsWork() {
        val rig = Rig().apply { request = { it, stop, _ ->
            it.state = it.state.copy(interactive = false)
            assertTrue(stop())
            Result.CANCELLED
        } }
        assertEquals(Outcome.CANCELLED, rig.run())
        assertEquals(1, rig.calls)
    }

    @Test fun radioFlappingResetsSettlingAndNeverResetsTheBudget() {
        val rig = Rig().apply {
            tick = { if (it.time == 2_500L) it.state = it.state.copy(radioRevision = 1) }
            request = { it, stop, _ ->
                it.state = it.state.copy(radioRevision = it.state.radioRevision + 1)
                assertTrue(stop())
                Result.CANCELLED
            }
        }
        assertEquals(Outcome.HOTSPOT_START_FAILED, rig.run())
        assertEquals(5_500L, rig.starts.first())
        assertEquals(3, rig.calls)
    }

    @Test fun externallyCancelledGenerationDoesNotReportSuccess() {
        val rig = Rig().apply { request = { it, stop, _ ->
            it.cancelled = true
            assertTrue(stop())
            Result.READY
        } }
        assertEquals(Outcome.CANCELLED, rig.run())
    }
}

package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessStartupPolicyTest {
    @Test fun nonTs7KeepsTheOriginalFifteenSecondTotalBudget() {
        val budgets = WirelessStartupPolicy.hotspotBudgets(ts7Detected = false)

        assertEquals(15_000L, budgets.tetheringMillis)
        assertEquals(15_000L, budgets.maxTetheringMillis)
        assertEquals(15_000L, budgets.totalMillis)
        assertFalse(budgets.extendWhileEnabling)
        assertEquals(0L, budgets.minimumInterfaceMillis)
        assertEquals(1_000L, budgets.interfaceReadinessMillis(1_000L))
        assertEquals(1L, budgets.interfaceReadinessMillis(0L))
    }

    @Test fun ts7CanExtendTetheringAndGetsAtLeastFiveSecondsForInterfaceReadiness() {
        val budgets = WirelessStartupPolicy.hotspotBudgets(ts7Detected = true)

        assertEquals(15_000L, budgets.tetheringMillis)
        assertEquals(30_000L, budgets.maxTetheringMillis)
        assertEquals(15_000L, budgets.totalMillis)
        assertTrue(budgets.extendWhileEnabling)
        assertEquals(5_000L, budgets.minimumInterfaceMillis)
        assertEquals(5_000L, budgets.interfaceReadinessMillis(1_000L))
        assertEquals(8_000L, budgets.interfaceReadinessMillis(8_000L))
    }

    @Test fun onlyTs7EnablingObservationsExtendTheDeadlineAndTheCapIsRespected() {
        val base = 15_000_000_000L
        val cap = 30_000_000_000L

        assertEquals(base, WirelessStartupPolicy.extendTetheringDeadline(base, cap, enabled = false))
        assertEquals(base + 250_000_000L,
            WirelessStartupPolicy.extendTetheringDeadline(base, cap, enabled = true))
        assertEquals(cap, WirelessStartupPolicy.extendTetheringDeadline(cap, cap, enabled = true))
        assertEquals(cap, WirelessStartupPolicy.extendTetheringDeadline(cap - 1, cap, enabled = true))
    }

    @Test fun noChannelMapsToANonRetryableHotspotConfigurationFailure() {
        val failure = hotspotStartupFailure(CarHotspotTethering.Result.NO_CHANNEL)

        assertEquals(WirelessStartupFailure.HOTSPOT_NO_CHANNEL, failure)
        assertFalse(failure.retryable)
    }
}

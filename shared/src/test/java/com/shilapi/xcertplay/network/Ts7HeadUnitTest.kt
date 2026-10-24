package com.shilapi.xcertplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ts7HeadUnitTest {
    @Test fun recognisesTheTs7DiagnosticReport() {
        // Build fields from the TS7's own diagnostic report.
        assertTrue(Ts7HeadUnit.matches("sp7731e_1h10", "sp7731e_1h10", "sp7731e_1h10", "sprd"))
        assertTrue(Ts7HeadUnit.matches("SP7731E_1H10", null, null, null))
        assertTrue(Ts7HeadUnit.matches(null, null, null, "SPRD"))
    }

    @Test fun leavesOtherHeadUnitsAlone() {
        assertFalse(Ts7HeadUnit.matches("ac8227l", "F9212A", "F9212A", "alps"))
        assertFalse(Ts7HeadUnit.matches("qcom", "msmnile", "byd_dilink", "BYD"))
        assertFalse(Ts7HeadUnit.matches(null, null, null, null))
        assertFalse(Ts7HeadUnit.detected) // Robolectric's Build
    }
}

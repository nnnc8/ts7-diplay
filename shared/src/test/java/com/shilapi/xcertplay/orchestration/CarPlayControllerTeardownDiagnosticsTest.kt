package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Test

class CarPlayControllerTeardownDiagnosticsTest {
    @Test fun includesCallerReasonWhenAvailable() {
        assertEquals(
            "teardown begin transport=WIRELESS reason=Settings saved; reconnecting",
            CarPlayController.teardownBeginDiagnostic("WIRELESS", "Settings saved; reconnecting"),
        )
    }

    @Test fun keepsExistingTeardownLineWhenCallerHasNoReason() {
        assertEquals(
            "teardown begin transport=WIRED",
            CarPlayController.teardownBeginDiagnostic("WIRED", null),
        )
    }
}

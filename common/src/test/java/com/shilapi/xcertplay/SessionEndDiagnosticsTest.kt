package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionEndDiagnosticsTest {
    @Test fun formatsSessionEndCauseAndActor() {
        assertEquals(
            "Session end requested reason=Disconnect button pressed by=user terminateProcess=false",
            SessionEndDiagnostics.requested("Disconnect button pressed", SessionEndBy.USER, false),
        )
        assertEquals(
            "Session end requested reason=foreground service could not start by=failure terminateProcess=false",
            SessionEndDiagnostics.requested("foreground service could not start", SessionEndBy.FAILURE, false),
        )
    }

    @Test fun recordsActivityStopAndDestroyFlags() {
        assertEquals("Activity stopped finishing=false", SessionEndDiagnostics.activityStopped(false))
        assertEquals(
            "Activity destroyed finishing=false changingConfigurations=true",
            SessionEndDiagnostics.activityDestroyed(false, true),
        )
    }
}

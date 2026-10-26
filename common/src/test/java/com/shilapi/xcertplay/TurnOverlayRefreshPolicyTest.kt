package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnOverlayRefreshPolicyTest {
    @Test fun ts7PollsOnlyWhileAnOverlayIsShown() {
        assertFalse(shouldRefreshTurnOverlay(ts7 = true, centerMapShown = false, sidePanelShown = false))
        assertTrue(shouldRefreshTurnOverlay(ts7 = true, centerMapShown = true, sidePanelShown = false))
        assertTrue(shouldRefreshTurnOverlay(ts7 = true, centerMapShown = false, sidePanelShown = true))
    }

    @Test fun otherHeadUnitsKeepTheirSafetyPoll() {
        assertTrue(shouldRefreshTurnOverlay(ts7 = false, centerMapShown = false, sidePanelShown = false))
    }
}

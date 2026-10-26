package com.shilapi.xcertplay

/** Non-TS7 units keep the existing safety poll; TS7 only polls while an overlay needs it. */
internal fun shouldRefreshTurnOverlay(ts7: Boolean, centerMapShown: Boolean, sidePanelShown: Boolean): Boolean =
    !ts7 || centerMapShown || sidePanelShown

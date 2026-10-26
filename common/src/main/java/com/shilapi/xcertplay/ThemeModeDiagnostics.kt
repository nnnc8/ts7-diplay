package com.shilapi.xcertplay

import android.content.res.Configuration

/** How often the host re-reads the configuration as a safety net beside onConfigurationChanged. */
internal const val CONFIGURATION_POLL_INTERVAL_MILLIS = 2_000L

/**
 * The poll only exists for firmware (e.g. BYD DiLink) that mutates resources.configuration without
 * delivering onConfigurationChanged. The TS7 delivers the callback, so its weak CPU keeps just a
 * slow safety net; the schedule night mode has its own minute tick in CarPlayNightModeController.
 */
internal const val TS7_CONFIGURATION_POLL_INTERVAL_MILLIS = 60_000L

internal fun configurationPollIntervalMillis(ts7: Boolean): Long =
    if (ts7) TS7_CONFIGURATION_POLL_INTERVAL_MILLIS else CONFIGURATION_POLL_INTERVAL_MILLIS

/** Samples only Android's existing theme signal; never infers a theme from vehicle state. */
internal class ThemeModeDiagnostics {
    enum class Source(val label: String, val force: Boolean = false) {
        CARPLAY_MODE("carplay-mode"),
        CREATE("create", true),
        START("start", true),
        STOP("stop", true),
        POLL("poll"),
        CALLBACK("configuration-callback"),
        WINDOW_FOCUS("window-focus"),
        SESSION_ACTIVE("session-active", true),
    }

    private data class State(val uiMode: Int, val appliedNight: Boolean, val sessionActive: Boolean)

    private var lastLoggedState: State? = null
    private var lastLoggedAt = 0L
    private var polls = 0
    private var callbacks = 0

    fun observe(
        source: Source,
        uiMode: Int,
        appliedNight: Boolean,
        sessionActive: Boolean,
        elapsedMillis: Long,
    ): String? {
        if (source == Source.POLL) polls++
        if (source == Source.CALLBACK) callbacks++
        val state = State(uiMode, appliedNight, sessionActive)
        if (!source.force && state == lastLoggedState && elapsedMillis - lastLoggedAt < 60_000L) return null

        val reported = when (nightModeOrNull(uiMode)) {
            true -> "dark"
            false -> "light"
            null -> "undefined"
        }
        val nightMask = uiMode and Configuration.UI_MODE_NIGHT_MASK
        val message = "THEME_DIAGNOSTIC sample source=${source.label} uiMode=0x${uiMode.toString(16)} " +
            "nightMask=0x${nightMask.toString(16)} reported=$reported " +
            "applied=${if (appliedNight) "dark" else "light"} sessionActive=$sessionActive " +
            "pollsSinceSample=$polls callbacksSinceSample=$callbacks"
        lastLoggedState = state
        lastLoggedAt = elapsedMillis
        polls = 0
        callbacks = 0
        return message
    }
}

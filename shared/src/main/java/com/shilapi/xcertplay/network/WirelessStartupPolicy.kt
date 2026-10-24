package com.shilapi.xcertplay.network

import java.io.IOException

object WirelessStartupPolicy {
    const val HOTSPOT_READY_MILLIS = 15_000L
    const val HOTSPOT_READY_MAX_MILLIS = 30_000L
    const val HOTSPOT_INTERFACE_MIN_MILLIS = 5_000L
    const val INTERFACE_POLL_MILLIS = 250L
    const val STABLE_SAMPLES = 3
    const val FIRST_TCP_MILLIS = 30_000L
    const val MAX_STARTUP_RETRIES = 5
    const val STABLE_SESSION_MILLIS = 60_000L

    internal data class HotspotBudgets(
        val tetheringMillis: Long,
        val maxTetheringMillis: Long,
        val totalMillis: Long,
        val extendWhileEnabling: Boolean,
        val minimumInterfaceMillis: Long,
    ) {
        fun interfaceReadinessMillis(remainingMillis: Long): Long =
            if (minimumInterfaceMillis > 0) maxOf(remainingMillis, minimumInterfaceMillis)
            else remainingMillis.coerceAtLeast(1L)
    }

    internal fun hotspotBudgets(ts7Detected: Boolean): HotspotBudgets = HotspotBudgets(
        tetheringMillis = HOTSPOT_READY_MILLIS,
        maxTetheringMillis = if (ts7Detected) HOTSPOT_READY_MAX_MILLIS else HOTSPOT_READY_MILLIS,
        totalMillis = HOTSPOT_READY_MILLIS,
        extendWhileEnabling = ts7Detected,
        minimumInterfaceMillis = if (ts7Detected) HOTSPOT_INTERFACE_MIN_MILLIS else 0L,
    )

    /** A repeated ENABLING observation may extend the window by one poll, but never past the cap. */
    internal fun extendTetheringDeadline(
        currentDeadlineNanos: Long,
        maximumDeadlineNanos: Long,
        enabled: Boolean,
    ): Long = if (enabled) minOf(
        currentDeadlineNanos + INTERFACE_POLL_MILLIS * 1_000_000L,
        maximumDeadlineNanos,
    ) else currentDeadlineNanos
}

enum class WirelessStartupFailure {
    HOTSPOT_NOT_READY, FIRST_TCP_TIMEOUT, HOTSPOT_CONFIGURATION,

    /** Non-retryable hotspot configuration error: the saved band is unavailable on this radio. */
    HOTSPOT_NO_CHANNEL,

    /** The head unit's Android Bluetooth accepts connections that reach no phone. */
    BLUETOOTH_UNUSABLE,

    /** Android 13+ asked to turn the car hotspot off for Wi-Fi Direct, and the driver said no. */
    WIFI_DIRECT_BESIDE_HOTSPOT;

    /** False when no further attempt can change what went wrong. */
    val retryable: Boolean get() = this != HOTSPOT_CONFIGURATION && this != HOTSPOT_NO_CHANNEL &&
        this != BLUETOOTH_UNUSABLE && this != WIFI_DIRECT_BESIDE_HOTSPOT
}

internal fun hotspotStartupFailure(result: CarHotspotTethering.Result): WirelessStartupFailure = when (result) {
    CarHotspotTethering.Result.TIMED_OUT, CarHotspotTethering.Result.FAILED -> WirelessStartupFailure.HOTSPOT_NOT_READY
    CarHotspotTethering.Result.NO_CHANNEL -> WirelessStartupFailure.HOTSPOT_NO_CHANNEL
    else -> WirelessStartupFailure.HOTSPOT_CONFIGURATION
}

class WirelessStartupException(val reason: WirelessStartupFailure, message: String) : IOException(message)

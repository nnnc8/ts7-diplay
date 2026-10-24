package com.shilapi.xcertplay.network

import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.net.wifi.WifiManager

/**
 * Reads whether the head unit's own Wi-Fi hotspot is on, for the "Car hotspot" link.
 *
 * The user can turn it on in car settings or opt into [CarHotspotTethering] after granting permission.
 */
object CarHotspotStatus {
    private const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    private const val EXTRA_WIFI_AP_STATE = "wifi_state"
    private const val EXTRA_WIFI_AP_MODE = "wifi_ap_mode"
    private const val EXTRA_WIFI_AP_FAILURE_REASON = "wifi_ap_error_code"

    /**
     * True/false from the Wi-Fi AP state, or null when the firmware hides it (then callers must
     * not block the connection). Interface flags are not used: BYD keeps wlan1 up with an address
     * while tethering is off.
     */
    fun isEnabled(context: Context): Boolean? {
        val app = context.applicationContext
        val wifi = app.getSystemService(WifiManager::class.java)
        return read(
            state = { wifi?.let { WifiManager::class.java.getMethod("getWifiApState").invoke(it) as? Int } },
            enabled = { wifi?.let { WifiManager::class.java.getMethod("isWifiApEnabled").invoke(it) as? Boolean } },
            sticky = { app.registerReceiver(null, IntentFilter(ACTION_WIFI_AP_STATE_CHANGED)) },
        )
    }

    /** The raw tethered AP state, or null when neither the platform getter nor its sticky broadcast knows it. */
    fun apState(context: Context): Int? {
        val app = context.applicationContext
        val wifi = app.getSystemService(WifiManager::class.java)
        return readApState(
            state = { wifi?.let { WifiManager::class.java.getMethod("getWifiApState").invoke(it) as? Int } },
            sticky = { app.registerReceiver(null, IntentFilter(ACTION_WIFI_AP_STATE_CHANGED)) },
        )
    }

    /** Failure reasons belong to the sticky tethered-AP failure state, not local-only AP broadcasts. */
    fun apFailureReason(context: Context): Int? {
        val app = context.applicationContext
        return readApFailureReason { app.registerReceiver(null, IntentFilter(ACTION_WIFI_AP_STATE_CHANGED)) }
    }

    internal fun stateEnabled(state: Int?): Boolean? = state?.takeIf { it in 10..14 }?.let { it == 13 }

    internal fun read(state: () -> Int?, enabled: () -> Boolean?, sticky: () -> Intent?): Boolean? {
        stateEnabled(runCatching(state).getOrNull())?.let { return it }
        runCatching(enabled).getOrNull()?.let { return it }
        val intent = runCatching(sticky).getOrNull() ?: return null
        // Local-only AP state says nothing about the car's tethered hotspot, including on multi-AP firmware.
        if (intent.hasExtra(EXTRA_WIFI_AP_MODE) && intent.getIntExtra(EXTRA_WIFI_AP_MODE, -1) != 1) return null
        return stateEnabled(intent.getIntExtra(EXTRA_WIFI_AP_STATE, -1))
    }

    internal fun readApState(state: () -> Int?, sticky: () -> Intent?): Int? {
        runCatching(state).getOrNull()?.takeIf { it in 10..14 }?.let { return it }
        val intent = runCatching(sticky).getOrNull() ?: return null
        if (!isTetheredApBroadcast(intent)) return null
        return intent.getIntExtra(EXTRA_WIFI_AP_STATE, -1).takeIf { it in 10..14 }
    }

    internal fun readApFailureReason(sticky: () -> Intent?): Int? {
        val intent = runCatching(sticky).getOrNull() ?: return null
        if (!isTetheredApBroadcast(intent) ||
            intent.getIntExtra(EXTRA_WIFI_AP_STATE, -1) != 14 ||
            !intent.hasExtra(EXTRA_WIFI_AP_FAILURE_REASON)
        ) return null
        return intent.getIntExtra(EXTRA_WIFI_AP_FAILURE_REASON, -1).takeIf { it >= 0 }
    }

    private fun isTetheredApBroadcast(intent: Intent): Boolean =
        !intent.hasExtra(EXTRA_WIFI_AP_MODE) || intent.getIntExtra(EXTRA_WIFI_AP_MODE, -1) == 1
}

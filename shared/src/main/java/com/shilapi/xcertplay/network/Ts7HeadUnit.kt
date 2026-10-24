package com.shilapi.xcertplay.network

import android.os.Build

/**
 * Identifies the Dowei TS7 head unit this fork is tuned for: Unisoc SC7731E (`sp7731e_1h10`, manufacturer
 * `sprd`), four Cortex-A7 cores, Android 8.1 (API 27), a 1280x720 screen, a 2.4 GHz-only Wi-Fi radio and the
 * `OMX.sprd.h264.decoder` video decoder.
 *
 * Every TS7 default is gated by [detected] and applies only while the driver has not chosen a value, so other
 * head units keep the stock behaviour, and unit tests (where `Build` reports Robolectric) never take this path.
 */
object Ts7HeadUnit {

    /**
     * Board and hardware name of the SC7731E reference design the TS7 firmware reports (hardware, board and
     * device are all `sp7731e_1h10`). The manufacturer alone does not count: other Unisoc head units keep `sprd`.
     */
    private const val PLATFORM = "sp7731e"

    internal fun matches(hardware: String?, board: String?, device: String?): Boolean =
        listOf(hardware, board, device).any { it?.lowercase()?.contains(PLATFORM) == true }

    val detected: Boolean = runCatching {
        matches(Build.HARDWARE, Build.BOARD, Build.DEVICE)
    }.getOrDefault(false)
}

package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** Stateless policy and existing string mapping; settings ownership stays in the Activity. */
internal object CarHotspotUiSupport {
    fun resultText(result: CarHotspotTethering.Result): Int = when (result) {
        CarHotspotTethering.Result.READY -> R.string.hotspot_control_on
        CarHotspotTethering.Result.PERMISSION_REQUIRED -> R.string.hotspot_control_missing
        CarHotspotTethering.Result.UNSUPPORTED -> R.string.hotspot_control_unsupported
        CarHotspotTethering.Result.TIMED_OUT -> R.string.hotspot_control_timeout
        else -> R.string.hotspot_control_failed
    }

    fun carHotspotOff(mode: WirelessHotspotMode, enabled: Boolean?, optedIn: Boolean, permitted: Boolean): Boolean =
        mode == WirelessHotspotMode.MANUAL && enabled == false && !(optedIn && permitted)

    fun startupProblem(result: CarHotspotTethering.Result?, shouldEnable: Boolean, enabled: Boolean?): CarHotspotTethering.Result? =
        result?.takeIf { it != CarHotspotTethering.Result.READY &&
            it != CarHotspotTethering.Result.CANCELLED && shouldEnable && enabled != true }
}

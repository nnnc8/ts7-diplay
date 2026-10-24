package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotStatus
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.telecom.PhoneAudioRoute

/** Starts the CarPlay host after boot when the user has enabled the startup option. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Starts the watcher for other apps' calls when that setting is on, even without opening DiPlay.
        PhoneAudioRoute.attach(context)
        val launchEnabled = AirPlayPersistence.loadAutoStartOnBoot(context)
        StartupDiagnosticSnapshot.received(context, launchEnabled)
        if (!launchEnabled) return

        startCarHotspot(context, launchEnabled)

        val launch = Intent(context, DiPlayActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        try {
            context.startActivity(launch)
            StartupDiagnosticSnapshot.launchResult(context)
        } catch (error: RuntimeException) {
            StartupDiagnosticSnapshot.launchResult(context, error)
            Log.w(TAG, "Boot auto-start could not launch DiPlayActivity", error)
        }
    }

    private fun startCarHotspot(context: Context, launchEnabled: Boolean) {
        val shouldEnable = CarHotspotSettings.shouldEnable(
            context,
            AirPlayPersistence.loadWirelessEnabled(context),
            AirPlayPersistence.loadWirelessHotspotMode(context),
        )
        val canWrite = Settings.System.canWrite(context)
        val alreadyOn = CarHotspotStatus.isEnabled(context) == true
        if (!BootHotspotStartPolicy.shouldStart(launchEnabled, shouldEnable, canWrite, alreadyOn)) return

        val pending = goAsync()
        try {
            Thread({
                try {
                    CarHotspotTethering.enable(
                        context,
                        isCancelled = { false },
                        timeoutMillis = HOTSPOT_START_TIMEOUT_MILLIS,
                        log = { Log.i(TAG, it) },
                    )
                } catch (error: Exception) {
                    Log.w(TAG, "Boot auto-start could not enable the car hotspot", error)
                } finally {
                    pending.finish()
                }
            }, "diplay-boot-hotspot").start()
        } catch (error: RuntimeException) {
            pending.finish()
            Log.w(TAG, "Boot auto-start could not start the hotspot worker", error)
        }
    }

    private companion object {
        const val TAG = "xcertplay-boot"
        const val HOTSPOT_START_TIMEOUT_MILLIS = 8_000L
    }
}

internal object BootHotspotStartPolicy {
    fun shouldStart(launchEnabled: Boolean, hotspotEnabled: Boolean, canWrite: Boolean, alreadyOn: Boolean): Boolean =
        launchEnabled && hotspotEnabled && canWrite && !alreadyOn
}

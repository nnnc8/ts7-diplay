package com.shilapi.xcertplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotStatus
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.network.CarHotspotWakeRecovery
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor

/** Opt-in observer, independent of Activity and CarPlay session lifetime. No wake lock or idle poll. */
class CarHotspotRecoveryService : Service() {
    private val worker = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    @Volatile private var generation = 0L
    @Volatile private var radioRevision = 0L
    @Volatile private var destroyed = false
    private var awake = false
    private var task: Future<*>? = null
    private val preferencesChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (!eligible(this)) stopSelf()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> sleep()
                Intent.ACTION_SCREEN_ON -> observe()
                Intent.ACTION_AIRPLANE_MODE_CHANGED, WifiManager.WIFI_STATE_CHANGED_ACTION,
                "android.net.wifi.WIFI_AP_STATE_CHANGED" -> {
                    // AP events are observations, not changes in station-radio readiness.
                    if (intent.action != "android.net.wifi.WIFI_AP_STATE_CHANGED") radioRevision++
                    observe()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSharedPreferences("diplay_car_hotspot", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(preferencesChanged)
        getSharedPreferences("xcertplay_airplay", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(preferencesChanged)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.auto_car_hotspot_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 7, Intent(this, DiPlayActivity::class.java)
            .putExtra("page", "settings"), PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        val notification = builder.setSmallIcon(if (Build.VERSION.SDK_INT >= 21)
            R.drawable.ic_diplay_notification else R.drawable.ic_carplay)
            .setContentTitle(getString(R.string.auto_car_hotspot_title)).setContentIntent(open)
            .setContentText(getString(R.string.built_in_car_hotspot)).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIFICATION_ID, notification)
        if (!eligible(this)) { stopSelf(); return START_NOT_STICKY }
        observe()
        return START_STICKY
    }

    private fun interactive(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= 20) power.isInteractive
            else @Suppress("DEPRECATION") power.isScreenOn
    }

    private fun sleep() {
        awake = false
        generation++
        task?.cancel(true)
        task = null
    }

    private fun observe() {
        if (!eligible(this)) { stopSelf(); return }
        if (!interactive()) { if (awake || task != null) sleep(); return }
        if (awake) return // Includes repeated screen-on and Activity resume after this cycle ended.
        awake = true
        val epoch = ++generation
        getSharedPreferences("diplay_car_hotspot", MODE_PRIVATE).edit().remove("last_recovery").apply()
        Log.i(TAG, "wake recovery: started generation=$epoch")
        task = worker.submit {
            val outcome = CarHotspotWakeRecovery.recover(
                cancelled = { destroyed || epoch != generation },
                state = {
                    val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    val wifiState = runCatching { wifi?.wifiState }.getOrNull()
                    CarHotspotWakeRecovery.State(eligible(this), interactive(),
                        Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0,
                        wifiState == WifiManager.WIFI_STATE_DISABLED || wifiState == WifiManager.WIFI_STATE_ENABLED,
                        CarHotspotStatus.isEnabled(this), CarPlayBackgroundSession.active, radioRevision)
                },
                enable = { cancelled, timeout -> CarHotspotTethering.enable(this, cancelled, timeout) {
                    Log.i(TAG, it)
                } },
                now = SystemClock::elapsedRealtime,
                pause = Thread::sleep,
            )
            // A retired worker cannot overwrite the diagnostic of a newer wake.
            if (!destroyed && epoch == generation) {
                getSharedPreferences("diplay_car_hotspot", MODE_PRIVATE).edit()
                    .putString("last_recovery", outcome.name).apply()
                Log.i(TAG, "wake recovery: ${outcome.name}")
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        sleep()
        unregisterReceiver(receiver)
        getSharedPreferences("diplay_car_hotspot", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(preferencesChanged)
        getSharedPreferences("xcertplay_airplay", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(preferencesChanged)
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DiPlay-Hotspot"
        private const val CHANNEL = "diplay_hotspot_recovery"
        private const val NOTIFICATION_ID = 7
        fun eligible(context: Context): Boolean = CarHotspotSettings.shouldEnable(context,
            AirPlayPersistence.loadWirelessEnabled(context), AirPlayPersistence.loadWirelessHotspotMode(context))

        fun latestResult(context: Context): CarHotspotTethering.Result? = when (
            context.getSharedPreferences("diplay_car_hotspot", MODE_PRIVATE).getString("last_recovery", null)) {
            "PERMISSION_REQUIRED" -> CarHotspotTethering.Result.PERMISSION_REQUIRED
            "HOTSPOT_UNSUPPORTED" -> CarHotspotTethering.Result.UNSUPPORTED
            "HOTSPOT_START_FAILED" -> CarHotspotTethering.Result.FAILED
            else -> null
        }

        /** Called while the settings Activity is visible, or from the existing boot receiver. */
        fun sync(context: Context) {
            val intent = Intent(context, CarHotspotRecoveryService::class.java)
            if (!eligible(context)) { context.stopService(intent); return }
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Hotspot wake observer could not start", error)
            }
        }
    }
}

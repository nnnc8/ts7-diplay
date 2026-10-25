package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Observe the head unit roles, without changing pairing or exporting any Bluetooth identifier. */
internal class CarBluetoothProfileDiagnostics(
    private val context: Context,
    private val phoneAddress: () -> String?,
    private val report: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var watching = false
    private var generation = 0
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val after30s = Runnable { probe("after30s") }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val profile = profileFor(intent.action) ?: return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val matches = runCatching { phoneAddress()?.let { it.equals(device?.address, true) } }.getOrNull()
            emit(transition(profile, intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1), matches,
                System.currentTimeMillis()))
        }
    }

    fun start() {
        if (watching) return
        val filter = IntentFilter().apply { ACTIONS.keys.forEach(::addAction) }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, filter)
            watching = true
        }.onFailure { emit("Bluetooth: car profiles observer unavailable=${it.javaClass.simpleName}") }
        probe("start")
    }

    fun sessionActive() {
        probe("handoff")
        handler.removeCallbacks(after30s)
        handler.postDelayed(after30s, 30_000L)
    }

    fun probe(moment: String) {
        val radio = runCatching { adapter }.getOrNull()
        if (radio == null) { emit("Bluetooth: car profiles moment=$moment unavailable=noAdapter"); return }
        val expectedGeneration = generation
        for ((profile, label) in listOf(11 to "a2dpSink", 16 to "hfpClient")) {
            runCatching {
                val requested = radio.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(id: Int, proxy: BluetoothProfile) {
                        try {
                            if (expectedGeneration != generation) return
                            val devices = proxy.connectedDevices
                            val phone = phoneAddress()
                            val matching = phone?.let { address -> devices.any { address.equals(it.address, true) } }
                            emit("Bluetooth: car profiles moment=$moment $label connected=${devices.size} " +
                                "carPlayPhone=${matching ?: "unknown"} atMs=${System.currentTimeMillis()}")
                        } catch (error: Exception) {
                            emit("Bluetooth: car profiles moment=$moment $label unavailable=${error.javaClass.simpleName}")
                        } finally { runCatching { radio.closeProfileProxy(id, proxy) } }
                    }
                    override fun onServiceDisconnected(id: Int) = Unit
                }, profile)
                if (!requested) emit("Bluetooth: car profiles moment=$moment $label unavailable=unsupported")
            }.onFailure { emit("Bluetooth: car profiles moment=$moment $label unavailable=${it.javaClass.simpleName}") }
        }
    }

    fun stop() {
        handler.removeCallbacks(after30s)
        if (watching) runCatching { context.unregisterReceiver(receiver) }
        watching = false
        generation++
    }

    private fun emit(line: String) { runCatching { report(line) } }

    companion object {
        private val ACTIONS = mapOf(
            "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED" to "a2dpSink",
            "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED" to "hfpClient",
        )
        fun profileFor(action: String?) = ACTIONS[action]
        fun transition(profile: String, state: Int, matchesPhone: Boolean?, atMs: Long) =
            "Bluetooth: car profiles change=$profile state=$state carPlayPhone=${matchesPhone ?: "unknown"} atMs=$atMs"
    }
}

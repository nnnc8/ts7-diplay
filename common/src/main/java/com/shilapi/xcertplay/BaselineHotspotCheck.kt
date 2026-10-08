// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.network.LocalOnlyHotspotManager
import java.io.Closeable

/** An explicit, bounded radio-only check. It never starts a phone or authentication session. */
internal class BaselineHotspotCheck(context: Context) : Closeable {
    private val app = context.applicationContext
    private val lock = Any()
    private var manager: LocalOnlyHotspotManager? = null
    private var closed = false
    private var running = false

    fun start(onFinished: (String) -> Unit) {
        synchronized(lock) {
            if (closed || running) return
            if (Build.VERSION.SDK_INT != 27) { onFinished("HOTSPOT_CHECK_API27_ONLY"); return }
            running = true
            save(app, "HOTSPOT_STARTING")
        }
        Thread({
            val candidate = try { LocalOnlyHotspotManager(app) } catch (_: Exception) {
                finish("HOTSPOT_START_FAILED", onFinished)
                return@Thread
            }
            synchronized(lock) {
                if (closed) { candidate.close(); return@Thread }
                manager = candidate
            }
            val code = try {
                candidate.start(15_000)
                "HOTSPOT_READY"
            } catch (error: Exception) {
                when {
                    error is SecurityException -> "PERMISSION_DENIED"
                    error.message == "HOTSPOT_SECURITY_UNSUPPORTED" -> "HOTSPOT_SECURITY_UNSUPPORTED"
                    error.message == "HOTSPOT_RADIO_INFO_UNAVAILABLE" -> "HOTSPOT_RADIO_INFO_UNAVAILABLE"
                    else -> "HOTSPOT_START_FAILED"
                }
            } finally { candidate.close() }
            finish(code, onFinished)
        }, "ts7-hotspot-check").start()
    }

    private fun finish(code: String, onFinished: (String) -> Unit) = synchronized(lock) {
        manager = null
        running = false
        if (!closed) {
            save(app, code)
            onFinished(code)
        }
    }

    override fun close() {
        val active = synchronized(lock) {
            closed = true
            if (running) save(app, "HOTSPOT_CHECK_CANCELLED")
            running = false
            manager.also { manager = null }
        }
        active?.close()
    }

    companion object {
        fun lastResult(context: Context): String = context.getSharedPreferences("ts7_baseline", Context.MODE_PRIVATE)
            .getString("hotspot_result", "HOTSPOT_NOT_TESTED")
            ?.takeIf { it in setOf("HOTSPOT_NOT_TESTED", "HOTSPOT_STARTING", "HOTSPOT_READY", "PERMISSION_DENIED",
                "HOTSPOT_SECURITY_UNSUPPORTED", "HOTSPOT_RADIO_INFO_UNAVAILABLE", "HOTSPOT_START_FAILED", "HOTSPOT_CHECK_CANCELLED") }
            ?: "HOTSPOT_NOT_TESTED"

        private fun save(context: Context, code: String) {
            context.getSharedPreferences("ts7_baseline", Context.MODE_PRIVATE).edit().putString("hotspot_result", code).apply()
        }
    }
}

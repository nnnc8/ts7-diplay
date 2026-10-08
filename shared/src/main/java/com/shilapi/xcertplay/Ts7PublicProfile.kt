package com.shilapi.xcertplay

import android.util.Log
import java.io.IOException

/** Public TS7 baseline policy. Vendor and lab implementations remain in the source tree. */
object Ts7PublicProfile {
    const val BYD_INTEGRATION_ENABLED = false
    const val RAW_PROTOCOL_TRACES_ENABLED = false
    const val SENSITIVE_CAPTURES_ENABLED = false
    const val AUTH_BLOCKED = "AUTH_BLOCKED"
}

/** A missing or unusable external provider is a terminal provisioning state, not session success. */
class AuthBlockedException : IOException(Ts7PublicProfile.AUTH_BLOCKED)

/** Shared by private report storage and logcat; never pass a Throwable's message or stack to logs. */
object PublicDiagnostics {
    private val sensitive = Regex("(?i)(pass(word|phrase)?|token|private.?key|certificate|pair.?record|ssid|(?:body|payload|wire|hex|head|csd[0-9]*|report)\\s*=|(?:challenge|signature|guidance|road|serial(?:number)?|controller(?:id|identifier)?|device(?:id|identifier)?|connectionid|usbtransport|transportidentifier|hostid|systembuid|identifier)\\s*[:=])")
    private val namedDevice = Regex("(?i)\\b(?:phone|device|peer|host)?name\\s*[:=]")
    private val failureDetail = Regex("(?i)\\b(?:failed|failure|error|exception|unavailable)\\s*[:=]")
    private val mac = Regex("(?i)(?<![0-9a-f])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![0-9a-f])")
    private val identifier = Regex("(?i)\\b[0-9a-f]{24,}\\b|\\b[0-9a-f]{8}-[0-9a-f-]{27,}\\b")
    private val address = Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])")
    private val ipv6 = Regex("(?i)(?:[0-9a-f]{1,4}:)*[0-9a-f]{0,4}::[0-9a-f:]*(?:%[a-z0-9_.-]+)?|(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}")
    private val url = Regex("(?i)\\b(?:https?|file)://[^\\s]+")
    private val endpoint = Regex("(?i)\\b(?:peer|host|address|server|path)=\\S+")
    private val failureCodes = setOf(
        Ts7PublicProfile.AUTH_BLOCKED, "CONNECTION_FAILED", "CONNECTION_IO_FAILED",
        "PERMISSION_DENIED", "CONFIG_INVALID", "WIFI_RESET_REQUIRED",
        "HOTSPOT_SECURITY_UNSUPPORTED", "HOTSPOT_START_FAILED", "HOTSPOT_RADIO_INFO_UNAVAILABLE",
    )

    fun redact(line: String): String? {
        if (line.contains("TRACE ") || line.contains("PHONE ") || line.any { it == '\n' || it == '\r' || it == '\u0000' }) return null
        if (sensitive.containsMatchIn(line) || namedDevice.containsMatchIn(line) || failureDetail.containsMatchIn(line)) return null
        return line.replace(url, "[endpoint]").replace(endpoint, "[endpoint]")
            .replace(mac, "[address]").replace(identifier, "[identifier]")
            .replace(address, "[ip]").replace(ipv6, "[ip]").take(700)
    }

    fun failureCode(message: String): String = message.takeIf { it in failureCodes } ?: "CONNECTION_FAILED"

    fun failureCode(error: Throwable): String {
        var node: Throwable? = error
        // Preserve fixed manager codes through the original controller's wrappers.
        // Bound traversal even for cyclic causes; never return arbitrary cause text.
        for (index in 0 until 8) {
            val current = node ?: break
            current.message?.takeIf { it in failureCodes }?.let { return it }
            node = current.cause
        }
        return when (error) {
            is AuthBlockedException -> Ts7PublicProfile.AUTH_BLOCKED
            is SecurityException -> "PERMISSION_DENIED"
            is IllegalArgumentException -> "CONFIG_INVALID"
            is IOException -> "CONNECTION_IO_FAILED"
            else -> "CONNECTION_FAILED"
        }
    }
}

/** Drop sensitive values before the Android log sink, including exception causes. */
object PublicLog {
    private fun write(priority: Int, tag: String, message: String, error: Throwable? = null): Int {
        val safe = PublicDiagnostics.redact(message) ?: return 0
        val detail = if (error == null) safe else "$safe code=${PublicDiagnostics.failureCode(error)}"
        return Log.println(priority, tag, detail)
    }
    fun i(tag: String, message: String): Int = write(Log.INFO, tag, message)
    fun d(tag: String, message: String): Int = write(Log.DEBUG, tag, message)
    fun w(tag: String, message: String): Int = write(Log.WARN, tag, message)
    fun w(tag: String, message: String, error: Throwable): Int = write(Log.WARN, tag, message, error)
    fun e(tag: String, message: String): Int = write(Log.ERROR, tag, message)
    fun e(tag: String, message: String, error: Throwable): Int = write(Log.ERROR, tag, message, error)
}

package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.orchestration.MfiTarget
import java.io.File
import java.security.MessageDigest

/** Checks externally provisioned local authentication without importing APK identity assets. */
internal object DiPlayBootstrap {
    @Synchronized fun ensure(context: Context) {
        if (AirPlayPersistence.loadMfiTarget(context) != MfiTarget.LOCAL) return
        val privateRoot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) context.noBackupFilesDir else context.filesDir
        val target = File(privateRoot, LocalMfiAuthenticationClient.DIRECTORY)
        try {
            LocalMfiAuthenticationClient.load(target)
        } catch (_: Exception) {
            // A matching pair is not proof of authorization; provisioning belongs to the operator.
            throw AuthBlockedException()
        }
    }

    fun deviceId(identity: AirPlayIdentity): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).take(6).toByteArray()
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }
}

internal object DiPlayPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
    fun phoneAddress(context: Context): String? = prefs(context).getString("phone_address", null)
    fun phoneName(context: Context): String = prefs(context).getString("phone_name", null) ?: "Your iPhone"
    fun savePhone(context: Context, address: String, name: String) {
        prefs(context).edit().putString("phone_address", address).putString("phone_name", name).apply()
    }
    fun autoConnect(context: Context) = prefs(context).getBoolean("auto_connect", false)
    fun saveAutoConnect(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("auto_connect", value).apply()
    }
}

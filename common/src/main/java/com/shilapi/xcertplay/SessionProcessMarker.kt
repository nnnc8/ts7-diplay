package com.shilapi.xcertplay

import android.util.AtomicFile
import java.io.File

/** The projection process persists its session state in a file shared with subsequent processes. */
internal class SessionProcessMarker(file: File, private val pid: Int, private val processStart: Long = PROCESS_START) {
    private val marker = AtomicFile(file)
    private val owner = "$pid $processStart"

    fun previous(): String? = runCatching {
        val saved = marker.readFully().toString(Charsets.UTF_8).trim()
        val fields = saved.split(' ')
        if (saved == owner || fields.size != 2 || fields.any { it.toLongOrNull() == null }) null
        else "Previous DiPlay process pid=${fields[0]} ended while a CarPlay session was active"
    }.getOrNull()

    fun active() {
        var output: java.io.FileOutputStream? = null
        try {
            output = marker.startWrite()
            output.write(owner.toByteArray(Charsets.UTF_8))
            marker.finishWrite(output)
        } catch (error: Exception) {
            marker.failWrite(output)
            throw error
        }
    }

    fun ended() {
        val saved = runCatching { marker.readFully().toString(Charsets.UTF_8).trim() }.getOrNull()
        if (saved == owner) marker.delete()
    }

    companion object { private val PROCESS_START = System.currentTimeMillis() }
}

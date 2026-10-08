package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class Ts7AudioCaptureTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun directCaptureCallerCannotCreateAFileInThePublicProfile() {
        val directory = File(temporary.root, "captures")
        AudioPacketCapture(directory, 96).use { capture ->
            capture.record(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), 7, IOException("SENTINEL"))
            assertFalse(capture.file.exists())
        }
        assertFalse(directory.exists())
    }

    @Test fun anExistingCaptureDirectoryStillDoesNotEnableRecording() {
        val directory = temporary.newFolder("captures")
        AudioPacketCapture(directory, 96).use {
            it.record(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6), null, null)
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }
}

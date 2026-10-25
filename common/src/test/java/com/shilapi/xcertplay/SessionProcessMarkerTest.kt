package com.shilapi.xcertplay

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SessionProcessMarkerTest {
    @Test fun aNewProcessSeesTheUnclosedSessionAndAnOldOwnerCannotClearIt() {
        val dir = Files.createTempDirectory("diplay-session-marker").toFile()
        try {
            val file = dir.resolve("active")
            val first = SessionProcessMarker(file, 100, 1000)
            first.active()
            assertNull(first.previous())
            val next = SessionProcessMarker(file, 101, 2000)
            assertEquals("Previous DiPlay process pid=100 ended while a CarPlay session was active", next.previous())
            next.active()
            first.ended()
            assertTrue(file.exists())
            next.ended()
            assertFalse(file.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun reusedPidAndMalformedMarkersAreHandled() {
        val dir = Files.createTempDirectory("diplay-session-marker").toFile()
        try {
            val file = dir.resolve("active")
            SessionProcessMarker(file, 100, 1000).active()
            val next = SessionProcessMarker(file, 100, 2000)
            assertNotNull(next.previous())
            file.writeText("invalid content")
            assertNull(next.previous())
            next.active()
            next.ended()
            assertNull(next.previous())
        } finally { dir.deleteRecursively() }
    }
}

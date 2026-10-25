package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AudioDiagnosticRecorderTest {
    @Test fun snapshotLinesAreDeliveredFromTheDedicatedBackgroundThread() {
        val currentThread = Thread.currentThread().name
        val lines = CopyOnWriteArrayList<String>()
        val callbackThread = arrayOfNulls<String>(1)
        val done = CountDownLatch(1)

        AudioDiagnosticRecorder.enqueue(
            AudioDiagnosticSnapshot("audio stats", "buffer stats", "decoder stats", "media"),
            null,
        ) { line ->
            callbackThread[0] = Thread.currentThread().name
            lines += line
            if (line == "decoder stats") done.countDown()
        }

        assertTrue("diagnostic callback did not finish", done.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("audio stats", "buffer stats", "decoder stats"), lines)
        assertEquals("carplay-audio-diagnostics", callbackThread[0])
        assertNotEquals(currentThread, callbackThread[0])
    }
}

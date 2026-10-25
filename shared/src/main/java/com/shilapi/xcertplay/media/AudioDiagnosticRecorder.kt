package com.shilapi.xcertplay.media

import android.media.AudioManager
import android.util.Log
import java.util.concurrent.Executors

/** Immutable audio-worker snapshot; system queries and report delivery run on one background thread. */
internal data class AudioDiagnosticSnapshot(
    val audioStatsLine: String,
    val bufferStatsLine: String,
    val decoderStatsLine: String?,
    val audioType: String,
)

internal object AudioDiagnosticRecorder {
    private val recorder = Executors.newSingleThreadExecutor { task ->
        Thread(task, "carplay-audio-diagnostics").apply { isDaemon = true }
    }

    fun enqueue(snapshot: AudioDiagnosticSnapshot, manager: AudioManager?, report: (String) -> Unit) {
        runCatching {
            recorder.execute {
                Log.i("DiPlay-AudioStats", snapshot.audioStatsLine)
                safeReport(report, snapshot.audioStatsLine)
                safeReport(report, snapshot.bufferStatsLine)
                manager?.let { audio -> runCatching {
                    safeReport(report, "Audio: output state audioType=${snapshot.audioType} mode=${audio.mode} " +
                        "musicVolume=${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/" +
                        "${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)} " +
                        "musicMuted=${audio.isStreamMute(AudioManager.STREAM_MUSIC)}")
                } }
                snapshot.decoderStatsLine?.let { line ->
                    Log.i("DiPlay-AudioStats", line)
                    safeReport(report, line)
                }
            }
        }
    }

    private fun safeReport(report: (String) -> Unit, line: String) {
        runCatching { report(line) }
    }
}

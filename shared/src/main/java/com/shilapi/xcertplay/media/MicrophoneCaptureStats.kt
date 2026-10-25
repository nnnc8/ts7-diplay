package com.shilapi.xcertplay.media

import android.media.MediaRecorder
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.diagnostics.DiagnosticAddressRedactor

internal enum class MicrophoneFailureStage {
    MIN_BUFFER, ENCODER, RECORDER_CREATION, RECORDER_INITIALIZATION, SOCKET_CREATION,
    RECORDING, READ, CAPTURE, START,
}

/** Capture-thread counters only. Never retains PCM, encoded packets, keys or endpoint metadata. */
internal class MicrophoneCaptureStats(
    config: MicrophoneConfig,
    private val report: (String) -> Unit,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private val type = typeAndSource(config.audioType).first
    private val format = format(config)
    private var metadata = metadata(config)
    private var windowStart = nowNs()
    private var readStart = windowStart
    private var recordingStartedNs: Long? = null
    private var firstReadMs: Long? = null
    private var capturedBytes = 0L
    private var reads = 0L
    private var zeroReads = 0L
    private var readErrors = 0L
    private var readMaxNs = 0L
    private var encodedFrames = 0L
    private var emptyEncodedFrames = 0L
    private var udpSent = 0L
    private var sendErrors = 0L
    private var lastSentNs: Long? = null
    private var sendGapMaxNs = 0L
    private var firstSendError: SendError? = null
    private var peakSample = 0
    private var routedDeviceType: Int? = null

    fun started(routeType: Int?, recordingState: Int? = null) {
        recordingStartedNs = nowNs()
        firstReadMs = null
        routedDeviceType = routeType
        emit("Microphone: start $metadata routedDeviceType=${routeType ?: "unknown"} recordingState=${recordingState ?: "unknown"}")
    }

    /** Call before the capture thread starts; [metadata] is not volatile. */
    fun useVoiceCommunicationSource() = useSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)

    fun useSource(source: Int) { metadata = "type=$type source=${sourceName(source)} $format" }

    fun reading() { readStart = nowNs() }

    fun read(count: Int) {
        val now = nowNs()
        reads++
        readMaxNs = maxOf(readMaxNs, (now - readStart).coerceAtLeast(0))
        when {
            count > 0 -> {
                capturedBytes += count
                if (firstReadMs == null) {
                    firstReadMs = recordingStartedNs?.let { ((now - it).coerceAtLeast(0)) / 1_000_000 }
                }
            }
            count == 0 -> zeroReads++
            else -> readErrors++
        }
    }

    /**
     * The loudest 16-bit little-endian sample sent in this window. Without it, a microphone that
     * delivers silence and an uplink the iPhone discards looked the same in a report (#647).
     */
    fun level(pcm: ByteArray) {
        var index = 0
        while (index + 1 < pcm.size) {
            val sample = (pcm[index].toInt() and 0xff) or (pcm[index + 1].toInt() shl 8)
            peakSample = maxOf(peakSample, kotlin.math.abs(sample))
            index += 2
        }
    }

    fun encoded(frameCount: Int, emptyCount: Int) {
        encodedFrames += frameCount.coerceAtLeast(0)
        emptyEncodedFrames += emptyCount.coerceAtLeast(0)
    }

    fun sent() {
        val now = nowNs()
        lastSentNs?.let { sendGapMaxNs = maxOf(sendGapMaxNs, (now - it).coerceAtLeast(0)) }
        lastSentNs = now
        udpSent++
    }

    fun sendFailed(error: Throwable, afterStop: Boolean) {
        sendErrors++
        if (firstSendError == null) firstSendError = SendError.from(error, afterStop)
    }

    fun restarted(attempt: Int, reason: String, routeType: Int?) {
        emit("Microphone: recording restarted $metadata attempt=$attempt reason=$reason " +
            "routedDeviceType=${routeType ?: "unknown"}")
    }

    fun failure(stage: MicrophoneFailureStage, error: Throwable? = null, code: Int? = null) {
        emit(failureMessage(metadata, stage, error, code))
    }

    fun flush(ended: Boolean = false, routeType: (() -> Int?)? = null, silence: (() -> String)? = null) {
        val now = nowNs()
        if (!ended && now - windowStart < REPORT_INTERVAL_NS) return
        if (routeType != null) routedDeviceType = runCatching { routeType() }.getOrNull()
        val silenceDetail = if (peakSample <= 0 && silence != null) runCatching { " " + silence() }.getOrDefault("") else ""
        val sendError = if (sendErrors > 0) {
            firstSendError?.let { " lastSendError=${it.name}:${it.message} afterStop=${it.afterStop}" }.orEmpty()
        } else {
            ""
        }
        emit("Microphone: stats $metadata routedDeviceType=${routedDeviceType ?: "unknown"} " +
            "captureBytes=$capturedBytes reads=$reads zeroReads=$zeroReads readErrors=$readErrors " +
            "readMaxMs=${readMaxNs / 1_000_000} firstReadMs=${firstReadMs ?: "none"} encodedFrames=$encodedFrames " +
            "emptyEncodedFrames=$emptyEncodedFrames udpSent=$udpSent sendErrors=$sendErrors$sendError " +
            "sendGapMaxMs=${sendGapMaxNs / 1_000_000} peakDbfs=${peakDbfs(peakSample)}$silenceDetail ended=$ended")
        windowStart = now
        capturedBytes = 0
        reads = 0
        zeroReads = 0
        readErrors = 0
        readMaxNs = 0
        encodedFrames = 0
        emptyEncodedFrames = 0
        udpSent = 0
        sendErrors = 0
        firstSendError = null
        sendGapMaxNs = 0
        peakSample = 0
    }

    private fun emit(message: String) { runCatching { report(message) } }

    companion object {
        private const val REPORT_INTERVAL_NS = 5_000_000_000L

        /** Whole dBFS of a 16-bit peak; "silent" when every sample was zero. */
        internal fun peakDbfs(peak: Int): String =
            if (peak <= 0) "silent" else Math.round(20 * kotlin.math.log10(peak / 32768.0)).toString()

        private val SOURCE_NAMES = mapOf(
            MediaRecorder.AudioSource.DEFAULT to "DEFAULT",
            MediaRecorder.AudioSource.MIC to "MIC",
            MediaRecorder.AudioSource.VOICE_UPLINK to "VOICE_UPLINK",
            MediaRecorder.AudioSource.VOICE_DOWNLINK to "VOICE_DOWNLINK",
            MediaRecorder.AudioSource.VOICE_CALL to "VOICE_CALL",
            MediaRecorder.AudioSource.CAMCORDER to "CAMCORDER",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
            MediaRecorder.AudioSource.REMOTE_SUBMIX to "REMOTE_SUBMIX",
            MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED",
            // Not public: the always-listening source of a car's own voice assistant.
            1999 to "HOTWORD",
        )

        internal fun sourceName(source: Int): String = SOURCE_NAMES[source] ?: source.toString()
        private fun typeAndSource(audioType: String): Pair<String, String> = when (audioType) {
            "telephony" -> "telephony" to "VOICE_COMMUNICATION"
            "speechrecognition" -> "speechrecognition" to "VOICE_RECOGNITION"
            else -> "other" to "MIC"
        }

        private fun format(config: MicrophoneConfig): String = "codec=${config.codec.name} " +
            "rate=${config.sampleRate} channels=${config.channels} frameMs=${config.frameMillis}"

        private fun metadata(config: MicrophoneConfig): String {
            val (type, source) = typeAndSource(config.audioType)
            return "type=$type source=$source ${format(config)}"
        }

        private fun failureMessage(
            metadata: String,
            stage: MicrophoneFailureStage,
            error: Throwable?,
            code: Int?,
        ): String = "Microphone: failure $metadata stage=${stage.name} " +
            "error=${error?.javaClass?.simpleName?.replace(Regex("[^A-Za-z0-9_]"), "")?.take(64) ?: "none"} " +
            "code=${code ?: "none"}"

        fun reportStartFailure(config: MicrophoneConfig, error: Throwable, report: (String) -> Unit) {
            runCatching { report(failureMessage(metadata(config), MicrophoneFailureStage.START, error, null)) }
        }
    }

    private data class SendError(val name: String, val message: String, val afterStop: Boolean) {
        companion object {
            fun from(error: Throwable, afterStop: Boolean): SendError {
                val name = error.javaClass.simpleName
                    .replace(Regex("[^A-Za-z0-9_]"), "")
                    .take(64)
                    .ifEmpty { "unknown" }
                val message = DiagnosticAddressRedactor.redactAddresses(error.message.orEmpty())
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(80)
                    .ifEmpty { "no-message" }
                return SendError(name, message, afterStop)
            }
        }
    }
}

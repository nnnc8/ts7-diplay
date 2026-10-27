package com.shilapi.xcertplay.airplay

import java.util.Locale

/** Receive-thread timing only: no payloads, endpoint addresses, or route data. */
internal class StreamReceiveStats(
    private val label: String,
    private val report: (String) -> Unit,
    private val decryptImplementation: () -> String = { AirPlayCrypto.chachaImplementation },
    private val includeTimingSummary: Boolean = false,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private var windowStart = nowNs()
    private var readStart = windowStart
    private var processingStart = windowStart
    private var packets = 0
    private var bytes = 0L
    private var maxReadNs = 0L
    private var readsOver250Ms = 0
    private var seqGapAfterReadOver250Ms = 0
    private var seqMissingAfterReadOver250Ms = 0
    private var lastReceivedNs = 0L
    private var maxInterArrivalNs = 0L
    private var maxProcessNs = 0L
    private var lastTimestamp: Int? = null
    private var nextSequence: Int? = null
    private var forwardGapPackets = 0
    private var sequenceGapEvents = 0
    private var maxSequenceGap = 0
    private var lastSequenceGap = "none"
    private var lastSequenceGapAtMs = -1L
    private var lateOrDuplicate = 0
    private var decryptSamples = 0
    private var decryptBytes = 0L
    private var decryptSumNs = 0L
    private var maxDecryptNs = 0L
    private val readMicros = if (includeTimingSummary) IntArray(MAX_TIMING_SAMPLES) else intArrayOf()
    private var readSamples = 0
    private val decryptMicros = if (includeTimingSummary) IntArray(MAX_TIMING_SAMPLES) else intArrayOf()
    private var decryptTimingSamples = 0

    fun reading() { readStart = nowNs() }

    fun received(size: Int, sequence: Int? = null, timestamp: Int? = null) {
        processingStart = nowNs()
        val readNs = processingStart - readStart
        maxReadNs = maxOf(maxReadNs, readNs)
        if (includeTimingSummary && readSamples < readMicros.size) {
            readMicros[readSamples++] = durationMicros(readNs)
        }
        if (readNs >= LONG_READ_NS) readsOver250Ms++
        if (lastReceivedNs != 0L) {
            maxInterArrivalNs = maxOf(maxInterArrivalNs, processingStart - lastReceivedNs)
        }
        lastReceivedNs = processingStart
        packets++
        bytes += size
        if (sequence != null) {
            val expected = nextSequence
            val delta = if (expected == null) 0 else (sequence - expected) and 0xffff
            if (delta < 0x8000) {
                forwardGapPackets += delta
                if (delta > 0) {
                    sequenceGapEvents++
                    if (readNs >= LONG_READ_NS) {
                        seqGapAfterReadOver250Ms++
                        seqMissingAfterReadOver250Ms += delta
                    }
                    maxSequenceGap = maxOf(maxSequenceGap, delta)
                    lastSequenceGap = "expected=$expected received=$sequence missing=$delta " +
                        "previousRtpTs=${lastTimestamp?.toUnsignedLong() ?: "unknown"} " +
                        "receivedRtpTs=${timestamp?.toUnsignedLong() ?: "unknown"}"
                    lastSequenceGapAtMs = (processingStart - windowStart) / 1_000_000L
                }
                nextSequence = (sequence + 1) and 0xffff
            } else lateOrDuplicate++
        }
        if (timestamp != null) lastTimestamp = timestamp
    }

    /** One payload opened in [durationNs]; reported only for streams that call it. */
    fun decrypted(durationNs: Long, size: Int) {
        decryptSamples++
        decryptBytes += size
        decryptSumNs += durationNs
        maxDecryptNs = maxOf(maxDecryptNs, durationNs)
        if (includeTimingSummary && decryptTimingSamples < decryptMicros.size) {
            decryptMicros[decryptTimingSamples++] = durationMicros(durationNs)
        }
    }

    fun processed() {
        maxProcessNs = maxOf(maxProcessNs, nowNs() - processingStart)
        flush()
    }

    fun flush(ended: Boolean = false) {
        val now = nowNs()
        if (!ended && now - windowStart < 5_000_000_000L) return
        runCatching { report("Receive: $label packets=$packets bytes=$bytes readMaxMs=${maxReadNs / 1_000_000} " +
            "processMaxUs=${maxProcessNs / 1000} seqForwardGaps=$forwardGapPackets " +
            "lateOrDuplicate=$lateOrDuplicate interArrivalMaxMs=${maxInterArrivalNs / 1_000_000} " +
            "seqGapEvents=$sequenceGapEvents seqGapMax=$maxSequenceGap " +
            "seqGapLast=[$lastSequenceGap] seqGapAtMs=$lastSequenceGapAtMs ended=$ended " +
            "windowMs=${(now - windowStart).coerceAtLeast(0) / 1_000_000} " +
            "readsOver250Ms=$readsOver250Ms seqGapAfterReadOver250Ms=$seqGapAfterReadOver250Ms " +
            "seqMissingAfterReadOver250Ms=$seqMissingAfterReadOver250Ms" + decryptSummary() +
            " decryptImpl=${decryptImplementation()}" +
            (if (includeTimingSummary) timingSummary() else "")) }
        windowStart = now
        decryptSamples = 0
        decryptBytes = 0
        decryptSumNs = 0
        maxDecryptNs = 0
        readSamples = 0
        decryptTimingSamples = 0
        packets = 0
        bytes = 0
        maxReadNs = 0
        readsOver250Ms = 0
        seqGapAfterReadOver250Ms = 0
        seqMissingAfterReadOver250Ms = 0
        maxInterArrivalNs = 0
        maxProcessNs = 0
        forwardGapPackets = 0
        sequenceGapEvents = 0
        maxSequenceGap = 0
        lastSequenceGap = "none"
        lastSequenceGapAtMs = -1L
        lateOrDuplicate = 0
    }

    private fun Int.toUnsignedLong(): Long = toLong() and 0xffff_ffffL

    private fun decryptSummary(): String {
        if (decryptSamples == 0) return ""
        val mbPerSecond = if (decryptSumNs == 0L) 0L else decryptBytes * 1_000L / decryptSumNs
        return " decryptAvgUs=${decryptSumNs / decryptSamples / 1000} decryptMaxUs=${maxDecryptNs / 1000} " +
            "decryptMBps=$mbPerSecond"
    }

    private fun timingSummary(): String {
        val sortedRead = readMicros.copyOf(readSamples).also { it.sort() }
        val sortedDecrypt = decryptMicros.copyOf(decryptTimingSamples).also { it.sort() }
        val readP50 = sortedRead.getOrNull((sortedRead.size - 1) / 2) ?: 0
        val decryptP50 = sortedDecrypt.getOrNull((sortedDecrypt.size - 1) / 2) ?: 0
        return " readMs=${formatMicrosAsMs(readP50)}/${formatNanosAsMs(maxReadNs)}" +
            " decryptMs=${formatMicrosAsMs(decryptP50)}/${formatNanosAsMs(maxDecryptNs)}"
    }

    private fun durationMicros(nanos: Long): Int =
        (nanos.coerceAtLeast(0L) / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun formatMicrosAsMs(micros: Int): String =
        String.format(Locale.US, "%.1f", micros / 1_000.0)

    private fun formatNanosAsMs(nanos: Long): String =
        String.format(Locale.US, "%.1f", nanos.coerceAtLeast(0L) / 1_000_000.0)

    private companion object {
        const val LONG_READ_NS = 250_000_000L
        const val MAX_TIMING_SAMPLES = 512
    }

}

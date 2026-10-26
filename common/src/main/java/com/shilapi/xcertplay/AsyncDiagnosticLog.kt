package com.shilapi.xcertplay

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Queued entries retain only their exact file target and a redacted, bounded metadata string. */
internal object AsyncDiagnosticLog {
    private sealed class Entry(open val target: SessionLogFile) {
        var droppedBefore = 0L
        data class Append(override val target: SessionLogFile, val line: String) : Entry(target)
        data class Reset(override val target: SessionLogFile, val header: String) : Entry(target)
        data class Close(override val target: SessionLogFile) : Entry(target)
    }

    private val writer = BoundedDiagnosticWriter<Entry>(
        onDropped = { entry, count -> entry.droppedBefore += count },
    ) { entry ->
        when (entry) {
            is Entry.Append -> {
                appendDropped(entry.target, entry.droppedBefore)
                entry.target.appendNow(entry.line)
            }
            is Entry.Reset -> {
                entry.target.resetNow(entry.header)
                appendDropped(entry.target, entry.droppedBefore)
            }
            is Entry.Close -> {
                appendDropped(entry.target, entry.droppedBefore)
                entry.target.closeNow()
            }
        }
    }

    fun append(target: SessionLogFile?, message: String, nowMillis: Long = System.currentTimeMillis()) {
        if (target == null) return
        val safe = DiagnosticRedactor.redact(message) ?: return
        enqueue(Entry.Append(target, timestamped(safe, nowMillis)))
    }

    internal fun appendLine(target: SessionLogFile, line: String) {
        val safe = DiagnosticRedactor.redact(line) ?: return
        enqueue(Entry.Append(target, safe))
    }

    internal fun reset(target: SessionLogFile, header: String) {
        // A redacted-away header still represents a new session and must truncate/create its file.
        val safe = DiagnosticRedactor.redact(header).orEmpty()
        enqueue(Entry.Reset(target, safe))
    }

    internal fun close(target: SessionLogFile) {
        enqueue(Entry.Close(target))
    }

    private fun enqueue(entry: Entry) {
        runCatching { writer.enqueue(entry) }
    }

    private fun appendDropped(target: SessionLogFile, count: Long) {
        if (count > 0) target.appendNow(timestamped("diagnostic log dropped $count lines", System.currentTimeMillis()))
    }

    private fun timestamped(message: String, nowMillis: Long): String =
        "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))}  $message"

    /** A diagnostic export calls this on its worker thread before reading session log files. */
    internal fun flush(timeoutMillis: Long = 30_000): Boolean = writer.awaitIdle(timeoutMillis)

    internal fun awaitIdle(timeoutMillis: Long): Boolean = flush(timeoutMillis)
}

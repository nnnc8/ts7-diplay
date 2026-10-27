package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class DiagnosticReportLogHeader(val name: String, val firstLine: String?)

/** Bounded, private diagnostics. Each write is redacted before touching storage. */
internal class SessionLogFile(
    val file: File,
    private val appVersionName: String? = null,
    private val appVersionCode: Long? = null,
    private val ts7: Boolean = false,
) : Closeable {
    private val lock = Any()
    private var closed = false
    internal val versionLabel: String? = appVersionName?.let { name ->
        appVersionCode?.let { "$name ($it)" } ?: name
    }

    /** All filesystem work is serialized by [AsyncDiagnosticLog]'s bounded background writer. */
    fun reset(header: String) = AsyncDiagnosticLog.reset(this, header)

    fun append(line: String) = AsyncDiagnosticLog.appendLine(this, line)

    override fun close() = AsyncDiagnosticLog.close(this)

    internal fun resetNow(header: String) = synchronized(lock) {
        if (!closed) {
            file.parentFile?.mkdirs()
            rotate()
            file.writeText("")
            appendNow(header)
        }
    }

    internal fun appendNow(line: String) = synchronized(lock) {
        if (closed) return@synchronized
        val safe = DiagnosticRedactor.redact(line) ?: return@synchronized
        runCatching {
            if (file.length() > MAX_BYTES) {
                rotate()
                file.writeText("")
                writeContinuedHeader()
            }
            file.appendText(safe + "\n")
        }
        Unit
    }

    internal fun closeNow() = synchronized(lock) { closed = true }

    private fun rotate() {
        if (!file.exists() || file.length() == 0L) return
        if (ts7) {
            file.copyTo(File(file.parentFile, ARCHIVE_NAMES.first()), overwrite = true)
            return
        }
        for (index in ARCHIVE_NAMES.lastIndex downTo 1) {
            val source = File(file.parentFile, ARCHIVE_NAMES[index - 1])
            val destination = File(file.parentFile, ARCHIVE_NAMES[index])
            if (source.exists()) source.copyTo(destination, overwrite = true)
        }
        file.copyTo(File(file.parentFile, ARCHIVE_NAMES.first()), overwrite = true)
    }

    private fun writeContinuedHeader() {
        val version = versionLabel ?: return
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        file.appendText("DiPlay log continued $timestamp version=$version\n")
    }

    companion object {
        const val MAX_BYTES = 512 * 1024L
        private val ARCHIVE_NAMES = listOf("previous.log") + (2..7).map { "previous-$it.log" }
        val REPORT_NAMES = ARCHIVE_NAMES.reversed() + "diplay.log"

        /** Delete the older archive files once, when the TS7 app process starts. */
        internal fun pruneTs7Archives(logDirectory: File, ts7: Boolean) {
            if (!ts7) return
            ARCHIVE_NAMES.drop(1).forEach { name ->
                runCatching { File(logDirectory, name).delete() }
            }
        }

        /** Returns existing report files in the historical order, filtered to this TS7 app version. */
        internal fun reportNamesToInclude(
            files: List<DiagnosticReportLogHeader>,
            currentVersion: String,
            ts7: Boolean,
        ): List<String> {
            val byName = files.associateBy(DiagnosticReportLogHeader::name)
            if (!ts7) return REPORT_NAMES.filter(byName::containsKey)

            return listOf("previous.log", "diplay.log").filter { name ->
                byName[name]?.firstLine?.let(::versionFromHeader) == currentVersion
            }
        }

        internal fun versionFromHeader(firstLine: String?): String? =
            firstLine?.let { VERSION_PATTERN.find(it)?.groupValues?.get(1) }

        private val VERSION_PATTERN = Regex("\\bversion=([^\\s]+)")
    }
}

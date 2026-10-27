package com.shilapi.xcertplay

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLogFilePolicyTest {
    private val currentVersion = "0.2.21-ts7-wudi81-v21.6"

    @Test fun ts7ReportIncludesOnlyTheCurrentVersionAndAtMostTwoNewestLogs() {
        val sameVersion = "DiPlay log started 2026-10-27 version=$currentVersion (55)"
        val headers = listOf(
            DiagnosticReportLogHeader("previous-2.log", sameVersion),
            DiagnosticReportLogHeader("diplay.log", sameVersion),
            DiagnosticReportLogHeader("previous.log", sameVersion),
        )

        assertEquals(
            listOf("previous.log", "diplay.log"),
            SessionLogFile.reportNamesToInclude(headers, currentVersion, ts7 = true),
        )
    }

    @Test fun ts7ReportExcludesOldVersionAndMissingVersionHeaders() {
        val headers = listOf(
            DiagnosticReportLogHeader("previous.log", "DiPlay log started version=0.2.21-ts7-wudi81-v21.5 (54)"),
            DiagnosticReportLogHeader("diplay.log", "legacy header without a version"),
        )

        assertTrue(SessionLogFile.reportNamesToInclude(headers, currentVersion, ts7 = true).isEmpty())
        assertEquals(null, SessionLogFile.versionFromHeader(headers.last().firstLine))
    }

    @Test fun nonTs7ReportKeepsAllEightLogsInTheExistingOldestFirstOrder() {
        val files = SessionLogFile.REPORT_NAMES.reversed().map { name ->
            DiagnosticReportLogHeader(name, null)
        }

        assertEquals(
            SessionLogFile.REPORT_NAMES,
            SessionLogFile.reportNamesToInclude(files, currentVersion, ts7 = false),
        )
    }

    @Test fun ts7PrunesOldArchivesAndContinuedLogStartsWithItsVersion() {
        val directory = Files.createTempDirectory("diplay-ts7-log-policy").toFile()
        try {
            (2..7).forEach { index -> directory.resolve("previous-$index.log").writeText("old") }
            val active = directory.resolve("diplay.log")
            active.writeText("DiPlay log started old-version\n")

            SessionLogFile.pruneTs7Archives(directory, ts7 = true)
            val log = SessionLogFile(active, currentVersion, 55L, ts7 = true)
            log.resetNow("DiPlay log started version=$currentVersion (55)")
            repeat(900) { log.appendNow("Video: stats " + "x".repeat(690)) }
            log.closeNow()

            assertEquals(setOf("diplay.log", "previous.log"), directory.list()!!.toSet())
            assertTrue(directory.resolve("previous.log").readLines().first().contains("version=$currentVersion (55)"))
            assertTrue(directory.resolve("diplay.log").readLines().first().contains("DiPlay log continued "))
            assertTrue(directory.resolve("diplay.log").readLines().first().contains("version=$currentVersion (55)"))
            assertFalse(directory.list()!!.any { it.startsWith("previous-") })
        } finally {
            directory.deleteRecursively()
        }
    }
}

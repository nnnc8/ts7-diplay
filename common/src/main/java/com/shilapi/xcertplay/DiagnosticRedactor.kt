package com.shilapi.xcertplay

/** Diagnostics describe state transitions; protocol payloads and credentials are never exported. */
internal object DiagnosticRedactor {
    fun redact(line: String): String? = PublicDiagnostics.redact(line)
}

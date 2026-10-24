package com.shilapi.xcertplay.diagnostics

/** Shared address and endpoint redaction for diagnostics emitted from shared code. */
object DiagnosticAddressRedactor {
    private val address = Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])")
    private val ipv6 = Regex(
        "(?i)(?:[0-9a-f]{1,4}:)*[0-9a-f]{0,4}::[0-9a-f:]*(?:%[a-z0-9_.-]+)?|(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}",
    )
    private val ipv4Endpoint = Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}:([0-9]{1,5})(?![0-9])")
    private val bracketedIpv6Endpoint = Regex("\\[[0-9a-f:]+(?:%[a-z0-9_.-]+)?]:(?:[0-9]{1,5})(?![0-9])", RegexOption.IGNORE_CASE)
    private val bracketedIpv6 = Regex("\\[[0-9a-f:]+(?:%[a-z0-9_.-]+)?]", RegexOption.IGNORE_CASE)
    private val labelledPort = Regex("(?i)(\\bport[=: ]+)\\d{1,5}\\b")

    fun redactAddresses(text: String): String {
        val endpointsRedacted = text
            .replace(ipv4Endpoint, "[ip]:[port]")
            .replace(bracketedIpv6Endpoint, "[ip]:[port]")
            .replace(labelledPort) { "${it.groupValues[1]}[port]" }
        return endpointsRedacted
            .replace(bracketedIpv6, "[ip]")
            .replace(address, "[ip]")
            .replace(ipv6, "[ip]")
    }
}

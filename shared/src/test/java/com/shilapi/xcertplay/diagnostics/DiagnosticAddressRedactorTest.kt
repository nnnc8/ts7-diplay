package com.shilapi.xcertplay.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticAddressRedactorTest {
    @Test fun redactsIpv4Ipv6AndPortsInEndpointText() {
        assertEquals(
            "peer=[ip]:[port] ipv6=[ip]:[port] port=[port]",
            DiagnosticAddressRedactor.redactAddresses(
                "peer=192.168.49.1:54321 ipv6=[fe80::1234:5678%p2p0]:7000 port=9000",
            ),
        )
    }

    @Test fun redactsAddressesWithoutEndpointPorts() {
        assertEquals(
            "peer=[ip] ipv6=[ip]",
            DiagnosticAddressRedactor.redactAddresses("peer=192.168.49.1 ipv6=fe80::1234:5678"),
        )
    }
}

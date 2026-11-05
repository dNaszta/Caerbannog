package dev.caerbannog.core.connections

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M2-14a step 2: the four default ports and the rule that the ports follow the TLS switch only while they are still the
 * default of the old mode (brief "Design notes"). Pure, no fakes. Red until `BrokerEndpoints` is built.
 */
class BrokerEndpointsTest {

    @Test
    fun `TLS on defaults are stream 5551 and management 15671`() {
        assertEquals(BrokerPorts(stream = 5551, management = 15671), BrokerEndpoints.defaultsFor(tls = true))
    }

    @Test
    fun `TLS off defaults are stream 5552 and management 15672`() {
        assertEquals(BrokerPorts(stream = 5552, management = 15672), BrokerEndpoints.defaultsFor(tls = false))
    }

    @Test
    fun `a profile built without ports still starts on the TLS on defaults`() {
        val profile = ConnectionProfile(name = "p", host = "h", virtualHost = "/", username = "u")
        assertEquals(BrokerEndpoints.defaultsFor(true), BrokerPorts(profile.streamPort, profile.managementPort))
        assertEquals(true, profile.tls, "TLS is on unless the user turns it off")
    }

    private data class Case(val label: String, val current: BrokerPorts, val tlsNow: Boolean, val expected: BrokerPorts)

    private val cases = listOf(
        Case("off, both untouched", BrokerPorts(5551, 15671), false, BrokerPorts(5552, 15672)),
        Case("off, stream edited", BrokerPorts(5600, 15671), false, BrokerPorts(5600, 15672)),
        Case("off, management edited", BrokerPorts(5551, 15700), false, BrokerPorts(5552, 15700)),
        Case("off, both edited", BrokerPorts(5600, 15700), false, BrokerPorts(5600, 15700)),
        Case("on, both untouched", BrokerPorts(5552, 15672), true, BrokerPorts(5551, 15671)),
        Case("on, stream edited", BrokerPorts(5600, 15672), true, BrokerPorts(5600, 15671)),
        Case("on, management edited", BrokerPorts(5552, 15700), true, BrokerPorts(5551, 15700)),
        Case("on, both edited", BrokerPorts(5600, 15700), true, BrokerPorts(5600, 15700)),
        // Already at the defaults of the new mode: nothing to move (switching to the mode the form is already in).
        Case("off, already plain defaults", BrokerPorts(5552, 15672), false, BrokerPorts(5552, 15672)),
        Case("on, already TLS defaults", BrokerPorts(5551, 15671), true, BrokerPorts(5551, 15671)),
        // Edited-then-equal-to-the-other-default counts as not edited (accepted in the brief): a stream port typed as 5552 with TLS on moves.
        Case("on to off, stream typed as the plain default stays", BrokerPorts(5552, 15671), false, BrokerPorts(5552, 15672)),
        // The ports are decided one by one: the stream port at the TLS default and the management port at the PLAIN default (mixed) only
        // moves the one that equals the default of the old mode.
        Case("off, mixed: stream at TLS default, management at plain default", BrokerPorts(5551, 15672), false, BrokerPorts(5552, 15672)),
    )

    @Test
    fun `the ports follow the switch only while they equal the default of the old mode`() {
        val failures = cases.filter { BrokerEndpoints.afterTlsSwitch(it.current, it.tlsNow) != it.expected }
            .map { "${it.label}: ${it.current} with tls=${it.tlsNow} gave ${BrokerEndpoints.afterTlsSwitch(it.current, it.tlsNow)}, expected ${it.expected}" }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `switching off and on again without edits returns to the TLS defaults`() {
        val off = BrokerEndpoints.afterTlsSwitch(BrokerEndpoints.defaultsFor(true), tlsNow = false)
        assertEquals(BrokerEndpoints.defaultsFor(true), BrokerEndpoints.afterTlsSwitch(off, tlsNow = true))
    }

    @Test
    fun `an edited port survives a round trip of the switch`() {
        val edited = BrokerPorts(5600, 15700)
        val off = BrokerEndpoints.afterTlsSwitch(edited, tlsNow = false)
        assertEquals(edited, BrokerEndpoints.afterTlsSwitch(off, tlsNow = true))
    }
}

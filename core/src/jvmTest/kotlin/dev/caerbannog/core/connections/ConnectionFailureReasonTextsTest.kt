package dev.caerbannog.core.connections

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M2-14c step 2: the texts of the reasons a Connect can fail with. The four existing ones stay as they are (scenario "A connection that
 * cannot be used says why"); the new one is the sentence of the Check, built from one constant so the two cannot drift (brief, "Core").
 * Red until `ConnectionFailureReason.NO_TLS_ANSWER` exists (compile error until then).
 */
class ConnectionFailureReasonTextsTest {

    @Test
    fun `the four existing reasons keep their texts`() {
        assertEquals("The broker could not be reached", ConnectionFailureReason.BROKER_NOT_REACHABLE)
        assertEquals("The certificate was not trusted", ConnectionFailureReason.CERTIFICATE_NOT_TRUSTED)
        assertEquals("No password is stored", ConnectionFailureReason.NO_PASSWORD_STORED)
        assertEquals("Credentials were refused", ConnectionFailureReason.CREDENTIALS_REFUSED)
    }

    @Test
    fun `the no TLS answer reason is the sentence of the Check, word for word`() {
        val sentence = "The broker did not answer the TLS handshake. It may not use TLS; turn off 'Use TLS' in this connection's settings if that is intended."
        assertEquals(sentence, ConnectionFailureReason.NO_TLS_ANSWER)
        assertEquals(sentence, checkResultMessage(ConnectionCheckResult.TlsRefused, tls = true), "Connect and Check must say the same")
    }

    @Test
    fun `only the no TLS answer reason names the switch, the certificate reason does not`() {
        listOf(
            ConnectionFailureReason.BROKER_NOT_REACHABLE, ConnectionFailureReason.CERTIFICATE_NOT_TRUSTED,
            ConnectionFailureReason.NO_PASSWORD_STORED, ConnectionFailureReason.CREDENTIALS_REFUSED, ConnectionFailureReason.CONNECTION_LOST,
        ).forEach { reason ->
            assertFalse(reason.contains("Use TLS") || reason.contains("turn off"), "'$reason' must not point at the switch")
        }
        assertTrue(ConnectionFailureReason.NO_TLS_ANSWER.contains("'Use TLS'"))
    }
}

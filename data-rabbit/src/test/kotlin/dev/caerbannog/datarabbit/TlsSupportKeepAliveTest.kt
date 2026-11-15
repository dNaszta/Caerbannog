package dev.caerbannog.datarabbit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M2-16 (b): the JDK client must give up an idle keep-alive connection before RabbitMQ's management listener does (it closes one after 5 s, and the
 * default refresh interval is 5 s, so every tick landed on that edge). The JDK reads `jdk.httpclient.keepalive.timeout` once, when its client class
 * loads, so the property is JVM-global and only works when it is set before the first JDK client exists: [TlsSupport] sets it in its initialiser, and
 * `JdkHttpClientCreationGuardTest` keeps it the only place that creates a JDK client.
 *
 * Only the rule and the property are asserted here, never the effect (a connection that is not reused): the whole test JVM shares one copy of the
 * JDK class, so any test that created a JDK client first would make an effect test depend on the order. The effect was shown once, by hand, in the
 * M2-16 verification (see the brief's Status).
 */
class TlsSupportKeepAliveTest {

    private val key = "jdk.httpclient.keepalive.timeout"

    @Test
    fun `with nothing set the rule asks for 2 seconds`() {
        assertEquals("2", TlsSupport.keepAliveTimeoutToApply(null))
    }

    @Test
    fun `a value that is already set is left alone, whatever it is, the empty string included`() {
        for (existing in listOf("10", "30", "2", "0", "1", "abc", "", " ")) {
            assertNull(TlsSupport.keepAliveTimeoutToApply(existing), "an explicit -D of [$existing] must win over the default")
        }
    }

    @Test
    fun `after building a client the property is a number of seconds below 5`() {
        // Whatever the property was (our 2, or a value a user or a test run set on purpose), it must be below the broker's 5 s afterwards.
        TlsSupport.buildHttpClient(BrokerTransport.Plain, 1_000).close()
        val value = System.getProperty(key)
        assertNotNull(value, "$key is not set after TlsSupport built a client: the JDK would keep idle connections for its default 30 s")
        val seconds = value.trim().toIntOrNull()
        assertNotNull(seconds, "$key is [$value], not a number of seconds")
        assertTrue(seconds!! in 1..4, "$key is $seconds s; it must be at least 1 and below the 5 s after which the broker closes an idle connection")
    }
}

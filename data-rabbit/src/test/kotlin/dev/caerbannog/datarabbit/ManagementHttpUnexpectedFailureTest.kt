package dev.caerbannog.datarabbit

import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * M5-08a round 1, F4: the transport boundary's contract is "every failure becomes a reply value and no exception text is kept". An unexpected
 * RuntimeException from the HTTP client (its text can carry a URL, a host or a credential) is [ManagementReply.Unreachable]; cancellation still
 * propagates (unless the holder was closed, as before); an Error is never caught.
 */
class ManagementHttpUnexpectedFailureTest {

    private val sentinel = "SENTINEL-url-http://user:hunter2@broker.example/api"
    private val url = "http://broker.example:15672/api/queues/%2F/orders"

    private suspend fun all(http: ManagementHttp): List<ManagementReply> = listOf(http.get(url), http.post(url, "{}"), http.put(url, "{}"), http.delete(url))

    private fun replies(failure: Throwable): List<ManagementReply> =
        M508a.http(MockEngine { throw failure }).use { runBlocking { all(it) } }

    @Test
    fun `a RuntimeException with a sentinel is Unreachable for every verb and the sentinel appears nowhere`() {
        for (failure in listOf(RuntimeException(sentinel), IllegalStateException(sentinel), UnsupportedOperationException(sentinel), NullPointerException(sentinel), ClassCastException(sentinel), ArrayIndexOutOfBoundsException(sentinel))) {
            val replies = replies(failure)
            assertEquals(List(4) { ManagementReply.Unreachable }, replies, failure::class.simpleName)
            assertFalse(replies.toString().contains("SENTINEL") || replies.toString().contains("hunter2"), replies.toString())
        }
    }

    @Test
    fun `a failure wrapped around another still gives Unreachable`() {
        assertEquals(List(4) { ManagementReply.Unreachable }, replies(RuntimeException("outer", IllegalStateException(sentinel))))
    }

    @Test
    fun `cancellation still propagates while the holder is open`() {
        M508a.http(MockEngine { throw CancellationException("the caller left") }).use { http ->
            runBlocking {
                for (call in listOf<suspend () -> Any>({ http.get(url) }, { http.post(url, "{}") }, { http.put(url, "{}") }, { http.delete(url) })) {
                    val caught = try { call(); null } catch (e: CancellationException) { e }
                    assertNotNull(caught, "a CancellationException was swallowed")
                }
            }
        }
    }

    @Test
    fun `an Error is not caught`() {
        for (error in listOf(AssertionError("an error"), StackOverflowError(), NotImplementedError("x"), OutOfMemoryError())) {
            M508a.http(MockEngine { throw error }).use { http ->
                runBlocking {
                    for (call in listOf<suspend () -> Any>({ http.get(url) }, { http.put(url, "{}") }, { http.delete(url) })) {
                        val caught = try { call(); null } catch (e: Error) { e }
                        assertNotNull(caught, "${error::class.simpleName} was swallowed")
                    }
                }
            }
        }
    }

    @Test
    fun `a closed holder answers Unreachable without calling the client`() {
        var calls = 0
        val http = M508a.http(MockEngine { calls++; throw RuntimeException(sentinel) })
        http.close()
        val replies = runBlocking { all(http) }
        assertEquals(List(4) { ManagementReply.Unreachable }, replies)
        assertEquals(0, calls)
        assertNull(null)
        assertTrue(replies.none { it is ManagementReply.Response })
    }
}

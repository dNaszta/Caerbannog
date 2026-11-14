package dev.caerbannog.datarabbit

import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.basicAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.URLParserException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.net.http.HttpTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException

/** What one management API call came back with. No exception text is kept: it could carry a host or a URL. */
internal sealed interface ManagementReply {
    data class Response(val status: Int, val body: String) : ManagementReply

    /** Timeout, refused connection, closed holder, malformed address. */
    data object Unreachable : ManagementReply

    /** The TLS handshake failed because the certificate chain was not trusted (wrong or missing CA). */
    data object CertificateRejected : ManagementReply

    /** The TLS handshake failed and no certificate was at fault: the broker probably does not speak TLS. */
    data object NoTlsAnswer : ManagementReply
}

/**
 * The HTTP client of one held connection and the one place that knows the password for it (D6: read once from the
 * store by the gateway, kept here, forgotten on [close]). Never logs; every failure is a [ManagementReply] value.
 */
internal class ManagementHttp(
    private val client: HttpClient,
    private val username: String,
    password: String,
) : AutoCloseable {

    @Volatile
    private var password: String? = password

    @Volatile
    private var closed = false

    private val unreachable = Attempt.Answered(ManagementReply.Unreachable)

    /**
     * A GET changes nothing on the broker, so one that met a dropped connection is sent once more (M2-16): RabbitMQ closes an idle keep-alive
     * connection after 5 s, and the JDK client's own single retry can land on a second dead pooled connection. At most two attempts, no loop, no
     * delay; the second outcome is final. Every other failure, and every write, keeps exactly one attempt, so [ManagementReply.Unreachable] still
     * means the broker did not answer a fresh attempt.
     */
    suspend fun get(url: String): ManagementReply {
        val call: suspend (secret: String) -> HttpResponse = { secret -> client.get(url) { basicAuth(username, secret) } }
        return when (val first = attempt(call)) {
            is Attempt.Answered -> first.reply
            Attempt.Dropped -> attempt(call).asReply()
        }
    }

    suspend fun post(url: String, body: String): ManagementReply = send { secret ->
        client.post(url) { sendJson(secret, body) }
    }

    /** Only the migration executor may call this (guard test); the path allow-list is its job. */
    suspend fun put(url: String, body: String): ManagementReply = send { secret ->
        client.put(url) { sendJson(secret, body) }
    }

    /** Only the migration executor may call this (guard test); the path allow-list is its job. */
    suspend fun delete(url: String): ManagementReply = send { secret ->
        client.delete(url) { basicAuth(username, secret) }
    }

    private fun HttpRequestBuilder.sendJson(secret: String, body: String) {
        basicAuth(username, secret)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    /** Exactly one attempt: the rule for every write, which must never be sent twice (a lost answer may hide an applied change). */
    private suspend fun send(call: suspend (secret: String) -> HttpResponse): ManagementReply = attempt(call).asReply()

    /**
     * The one place for the transport rules of every verb, and the one catch ladder: nothing is sent once the holder is closed (so no
     * credential leaves before or after the connection's life), every failure becomes a value, and no exception text or body is kept.
     * [closed] and the password are read here, so a second attempt of a GET checks them again. The TLS or plain choice is the client's,
     * made from the profile (D9a).
     *
     * [Attempt.Dropped] is only the last `IOException` catch: the connection was lost without an answer. Every type that is an
     * `IOException` but not that (a TLS failure, a timeout, an interruption, a refused connection, an unknown host, no route) is caught
     * before it, in an answered branch, because retrying those would repeat a wait or a failure that cannot change. `ConnectException`
     * and `NoRouteToHostException` are `SocketException`s, so a bare `SocketException` (reset, broken pipe) is a dropped connection and
     * they are not.
     */
    private suspend fun attempt(call: suspend (secret: String) -> HttpResponse): Attempt {
        val secret = password
        if (closed || secret == null) return unreachable
        return try {
            val response = call(secret)
            Attempt.Answered(ManagementReply.Response(response.status.value, response.bodyAsText()))
        } catch (e: CancellationException) {
            // A client closed under a running call reports cancellation; that is a lost connection, not the caller leaving.
            if (closed) unreachable else throw e
        } catch (e: SSLException) {
            Attempt.Answered(classify(e))
        } catch (e: HttpRequestTimeoutException) {
            unreachable
        } catch (e: ConnectTimeoutException) {
            unreachable
        } catch (e: HttpTimeoutException) {
            unreachable
        } catch (e: SocketTimeoutException) {
            unreachable
        } catch (e: InterruptedIOException) {
            unreachable
        } catch (e: ConnectException) {
            unreachable
        } catch (e: NoRouteToHostException) {
            unreachable
        } catch (e: UnknownHostException) {
            unreachable
        } catch (e: IOException) {
            Attempt.Dropped
        } catch (e: URISyntaxException) {
            unreachable
        } catch (e: URLParserException) {
            unreachable
        } catch (e: IllegalArgumentException) {
            unreachable
        } catch (e: RuntimeException) {
            // The transport boundary: its contract is that every failure becomes a reply value and no exception text is kept (it could carry
            // a URL or a secret), so a broad catch is justified here and only here. An Error is never caught.
            unreachable
        }
    }

    private fun Attempt.asReply(): ManagementReply = when (this) {
        is Attempt.Answered -> reply
        Attempt.Dropped -> ManagementReply.Unreachable
    }

    /** What one attempt came to: a reply, or a connection lost without an answer, which a GET may try once more. Never leaves this class. */
    private sealed interface Attempt {
        data class Answered(val reply: ManagementReply) : Attempt

        data object Dropped : Attempt
    }

    override fun close() {
        closed = true
        password = null
        client.close()
    }

    internal companion object {
        /**
         * One place that tells the two user actions apart (set the CA, or turn TLS off). Rule: a CertificateException
         * anywhere in the cause chain (walked to a depth limit, so a cause loop ends) means the certificate was rejected;
         * every other SSLException is "no TLS answer". Measured on JDK 21: a plain broker is a bare SSLException, a wrong
         * CA is an SSLHandshakeException caused by a CertificateException. Shapes not measured (a server that closes the
         * handshake, a fatal alert, a required client certificate) fall into NoTlsAnswer on purpose: the hint is
         * conditional and changes nothing.
         */
        fun classify(error: SSLException): ManagementReply =
            if (TlsExceptionClassifier.hasCertificateCause(error)) ManagementReply.CertificateRejected
            else ManagementReply.NoTlsAnswer
    }

}

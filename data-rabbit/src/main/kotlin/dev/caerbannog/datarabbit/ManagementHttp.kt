package dev.caerbannog.datarabbit

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLParserException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URISyntaxException
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

    suspend fun get(url: String): ManagementReply {
        val secret = password
        if (closed || secret == null) return ManagementReply.Unreachable
        return try {
            val response = client.get(url) { basicAuth(username, secret) }
            ManagementReply.Response(response.status.value, response.bodyAsText())
        } catch (e: CancellationException) {
            // A client closed under a running call reports cancellation; that is a lost connection, not the caller leaving.
            if (closed) ManagementReply.Unreachable else throw e
        } catch (e: SSLException) {
            classify(e)
        } catch (e: HttpRequestTimeoutException) {
            ManagementReply.Unreachable
        } catch (e: HttpTimeoutException) {
            ManagementReply.Unreachable
        } catch (e: SocketTimeoutException) {
            ManagementReply.Unreachable
        } catch (e: IOException) {
            ManagementReply.Unreachable
        } catch (e: URISyntaxException) {
            ManagementReply.Unreachable
        } catch (e: URLParserException) {
            ManagementReply.Unreachable
        } catch (e: IllegalArgumentException) {
            ManagementReply.Unreachable
        }
    }

    override fun close() {
        closed = true
        password = null
        client.close()
    }

    internal companion object {
        private const val MAX_CAUSE_DEPTH = 20

        /**
         * One place that tells the two user actions apart (set the CA, or turn TLS off). Rule: a CertificateException
         * anywhere in the cause chain (walked to a depth limit, so a cause loop ends) means the certificate was rejected;
         * every other SSLException is "no TLS answer". Measured on JDK 21: a plain broker is a bare SSLException, a wrong
         * CA is an SSLHandshakeException caused by a CertificateException. Shapes not measured (a server that closes the
         * handshake, a fatal alert, a required client certificate) fall into NoTlsAnswer on purpose: the hint is
         * conditional and changes nothing.
         */
        fun classify(error: SSLException): ManagementReply {
            var cause: Throwable? = error
            var depth = 0
            while (cause != null && depth < MAX_CAUSE_DEPTH) {
                if (cause is CertificateException) return ManagementReply.CertificateRejected
                cause = cause.cause
                depth++
            }
            return ManagementReply.NoTlsAnswer
        }
    }
}

package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.BrokerPermissions
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.PermissionChecker
import dev.caerbannog.core.connections.UnreadableCause
import dev.caerbannog.core.connections.decidePermissions
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLParserException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.URLEncoder
import java.net.URISyntaxException
import java.net.http.HttpTimeoutException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/**
 * Reads the connection user's permissions with `GET /api/permissions/{vhost}/{user}` over HTTPS, or plain HTTP when the profile has TLS off.
 * Spike (M1-03): only administrators may call it; everyone else gets 401, which comes back as
 * [UnreadableCause.NOT_AUTHORIZED] (a wrong password looks the same). Never returns DeclaredByUser.
 * `detail` carries only the status code or exception class: no password, URL or response body.
 */
class ManagementApiPermissionChecker(
    private val connectTimeoutMillis: Long = 5_000,
    private val requestTimeoutMillis: Long = connectTimeoutMillis,
) : PermissionChecker {

    override suspend fun check(profile: ConnectionProfile, password: String): PermissionCheckResult {
        val transport = try {
            BrokerTransport.from(profile)
        } catch (e: IOException) {
            return unreadable(UnreadableCause.UNREACHABLE, "CA certificate not loadable (${e.javaClass.simpleName})")
        } catch (e: java.security.GeneralSecurityException) {
            return unreadable(UnreadableCause.UNREACHABLE, "CA certificate not loadable (${e.javaClass.simpleName})")
        }

        val client = TlsSupport.buildHttpClient(transport, connectTimeoutMillis, requestTimeoutMillis)
        try {
            val response = client.get(permissionsUrl(profile, transport)) { basicAuth(profile.username, password) }
            return when (response.status) {
                HttpStatusCode.OK -> parse(response.bodyAsText())
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden ->
                    unreadable(UnreadableCause.NOT_AUTHORIZED, "HTTP ${response.status.value}")
                HttpStatusCode.NotFound -> unreadable(UnreadableCause.NOT_FOUND, "HTTP 404")
                else -> unreadable(UnreadableCause.BAD_RESPONSE, "HTTP ${response.status.value}")
            }
        } catch (e: HttpRequestTimeoutException) {
            return unreadable(UnreadableCause.TIMEOUT, e.javaClass.simpleName)
        } catch (e: HttpTimeoutException) {
            // Parent of HttpConnectTimeoutException; must come before IOException.
            return unreadable(UnreadableCause.TIMEOUT, e.javaClass.simpleName)
        } catch (e: SocketTimeoutException) {
            return unreadable(UnreadableCause.TIMEOUT, e.javaClass.simpleName)
        } catch (e: SSLException) {
            return unreadable(UnreadableCause.UNREACHABLE, "TLS failure (${e.javaClass.simpleName})")
        } catch (e: IOException) {
            return unreadable(UnreadableCause.UNREACHABLE, e.javaClass.simpleName)
        } catch (e: URISyntaxException) {
            // Observed (Ktor 3.0.3, Java engine): a host with a space.
            return unreadable(UnreadableCause.UNREACHABLE, e.javaClass.simpleName)
        } catch (e: URLParserException) {
            // Observed: port out of range (70000, -1). Extends IllegalStateException.
            return unreadable(UnreadableCause.UNREACHABLE, e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            return unreadable(UnreadableCause.UNREACHABLE, e.javaClass.simpleName)
        } finally {
            client.close()
        }
    }

    private fun permissionsUrl(profile: ConnectionProfile, transport: BrokerTransport): String =
        "${transport.scheme}://${profile.host}:${profile.managementPort}/api/permissions/${encode(profile.virtualHost)}/${encode(profile.username)}"

    // URLEncoder makes spaces "+", which a path segment does not understand.
    private fun encode(segment: String): String = URLEncoder.encode(segment, Charsets.UTF_8).replace("+", "%20")

    private fun parse(body: String): PermissionCheckResult {
        val obj: JsonObject = try {
            Json.parseToJsonElement(body).jsonObject
        } catch (e: SerializationException) {
            return unreadable(UnreadableCause.BAD_RESPONSE, "malformed JSON")
        } catch (e: IllegalArgumentException) {
            return unreadable(UnreadableCause.BAD_RESPONSE, "malformed JSON")
        }
        fun field(name: String): String? = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val configure = field("configure")
        val write = field("write")
        val read = field("read")
        if (configure == null || write == null || read == null) {
            return unreadable(UnreadableCause.BAD_RESPONSE, "missing permission fields")
        }
        return decidePermissions(BrokerPermissions(configure, write, read))
    }

    private fun unreadable(cause: UnreadableCause, detail: String) = PermissionCheckResult.Unreadable(cause, detail)
}

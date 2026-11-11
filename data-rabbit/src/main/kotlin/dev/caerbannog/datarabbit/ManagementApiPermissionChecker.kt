package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.BrokerPermissions
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.PermissionChecker
import dev.caerbannog.core.connections.UnreadableCause
import dev.caerbannog.core.connections.decidePermissions
import dev.caerbannog.core.json.UntrustedJson
import dev.caerbannog.core.json.parseUntrustedJson
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLParserException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.URLEncoder
import java.net.URISyntaxException
import java.net.http.HttpTimeoutException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/** The permissions reply is one small object; far below the reader's failure point near 10,000. */
private const val MAX_BODY_DEPTH = 64

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
                HttpStatusCode.OK -> parse(response.bodyAsText()).withTags { readTags(client, profile, transport, password) }
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

    private suspend fun PermissionCheckResult.withTags(read: suspend () -> Set<String>): PermissionCheckResult =
        if (this is PermissionCheckResult.Checked) copy(userTags = read()) else this

    /**
     * The tags of the connection's user from `GET /api/whoami`, asked AFTER the permissions in the same check (M8-12c; a JSON array on 3.9+ and 4.x, a
     * comma string is accepted). Not a secret and only used to enable the access migration, so a failure here is not a failure of the check: an empty
     * set, never a guess, and the administrator actions stay disabled.
     */
    private suspend fun readTags(client: io.ktor.client.HttpClient, profile: ConnectionProfile, transport: BrokerTransport, password: String): Set<String> =
        try {
            val url = "${transport.scheme}://${ManagementRequests.formatAuthority(profile.host, profile.managementPort)}/api/whoami"
            val response = client.get(url) { basicAuth(profile.username, password) }
            if (response.status != HttpStatusCode.OK) {
                emptySet()
            } else {
                val obj = (parseUntrustedJson(response.bodyAsText(), MAX_BODY_DEPTH) as? UntrustedJson.Parsed)?.element as? JsonObject
                when (val tags = obj?.get("tags")) {
                    is kotlinx.serialization.json.JsonArray ->
                        if (tags.all { it is JsonPrimitive && it.isString }) tags.map { (it as JsonPrimitive).content }.toSet() else emptySet()
                    is JsonPrimitive -> if (tags.isString) tags.content.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet() else emptySet()
                    else -> emptySet()
                }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptySet()
        }

    private fun permissionsUrl(profile: ConnectionProfile, transport: BrokerTransport): String =
        "${transport.scheme}://${ManagementRequests.formatAuthority(profile.host, profile.managementPort)}/api/permissions/${encode(profile.virtualHost)}/${encode(profile.username)}"

    // URLEncoder makes spaces "+", which a path segment does not understand.
    private fun encode(segment: String): String = URLEncoder.encode(segment, Charsets.UTF_8).replace("+", "%20")

    private fun parse(body: String): PermissionCheckResult {
        val obj: JsonObject = ((parseUntrustedJson(body, MAX_BODY_DEPTH) as? UntrustedJson.Parsed)?.element as? JsonObject)
            ?: return unreadable(UnreadableCause.BAD_RESPONSE, "malformed JSON")
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

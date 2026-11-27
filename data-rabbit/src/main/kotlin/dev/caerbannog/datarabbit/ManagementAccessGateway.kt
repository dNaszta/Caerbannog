package dev.caerbannog.datarabbit

import dev.caerbannog.core.access.AccessGateway
import dev.caerbannog.core.access.AccessResult
import dev.caerbannog.core.access.SecretGuard
import dev.caerbannog.core.access.WhoAmI
import dev.caerbannog.core.connections.isMigrationSourceOnly
import dev.caerbannog.core.json.UntrustedJson
import dev.caerbannog.core.json.parseUntrustedJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The [AccessGateway] over the management API of the held connection ([http], [requests]): GET only, the profile's own scheme and port, and every
 * string that leaves passes [SecretGuard] (with the held secret from [heldSecret], read per call and never stored), so the data is already
 * redacted when it leaves `data-rabbit`. A known 3.x [brokerVersion] refuses and sends nothing; an unknown version does not (same rule as
 * `isMigrationSourceOnly`). No broker body is ever put in a result: a refusal is a type, a failure is a status text or a fixed sentence.
 */
internal class ManagementAccessGateway(
    private val http: ManagementHttp,
    private val requests: ManagementRequests,
    private val brokerVersion: String?,
    private val heldSecret: () -> String? = { null },
) : AccessGateway {
    override suspend fun whoAmI(): AccessResult<WhoAmI> = read(requests.whoami(), ::parseWhoAmI)

    override suspend fun virtualHosts(): AccessResult<List<String>> = read(requests.vhosts(), ::parseVirtualHosts)

    private suspend fun <T> read(url: String, parse: (JsonElement, String?) -> T?): AccessResult<T> {
        if (isMigrationSourceOnly(brokerVersion)) return AccessResult.NotOffered
        return when (val reply = http.get(url)) {
            ManagementReply.Unreachable, ManagementReply.CertificateRejected, ManagementReply.NoTlsAnswer -> AccessResult.Unreachable
            is ManagementReply.Response -> when (reply.status) {
                200 -> parseBody(reply.body, parse)
                401 -> if (ManagementErrors.isNotManagementUser(reply.body)) AccessResult.NoManagementTag else AccessResult.NotAuthorised
                403 -> AccessResult.NotAuthorised
                else -> AccessResult.Unexpected("HTTP ${reply.status}")
            }
        }
    }

    private fun <T> parseBody(body: String, parse: (JsonElement, String?) -> T?): AccessResult<T> {
        val root = (parseUntrustedJson(body, MAX_JSON_DEPTH) as? UntrustedJson.Parsed)?.element
        val value = root?.let { parse(it, heldSecret()) }
        return if (value == null) AccessResult.Unexpected(NOT_UNDERSTOOD) else AccessResult.Loaded(value)
    }

    private fun parseWhoAmI(root: JsonElement, secret: String?): WhoAmI? {
        val obj = root as? JsonObject ?: return null
        val name = text(obj["name"]) ?: return null
        val tags = (obj["tags"] as? JsonArray)?.map { text(it) ?: return null } ?: emptyList()
        // A missing flag is not a token user: only an explicit false is the signal (M10-00, S-A).
        val internal = (obj["is_internal_user"] as? JsonPrimitive)?.content != "false"
        val shown = SecretGuard.redactAll(listOf(name) + tags, secret).values
        return WhoAmI(shown.first(), shown.drop(1), internal)
    }

    private fun parseVirtualHosts(root: JsonElement, secret: String?): List<String>? {
        val names = (root as? JsonArray)?.map { text((it as? JsonObject)?.get("name")) ?: return null } ?: return null
        return SecretGuard.redactAll(names, secret).values
    }

    private fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        const val MAX_JSON_DEPTH = 64
        const val NOT_UNDERSTOOD = "The answer was not understood."
    }
}

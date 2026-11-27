package dev.caerbannog.datarabbit

import dev.caerbannog.core.access.AccessGateway
import dev.caerbannog.core.access.AccessResult
import dev.caerbannog.core.access.PatternCell
import dev.caerbannog.core.access.PatternReading
import dev.caerbannog.core.access.PermissionRow
import dev.caerbannog.core.access.SecretGuard
import dev.caerbannog.core.access.TopicPermissionRow
import dev.caerbannog.core.access.UserNames
import dev.caerbannog.core.access.UserPermissions
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

    override suspend fun users(): AccessResult<UserNames> = read(requests.users(), ::parseUsers)

    /** Two GETs; a result only when both parse, so the tab never draws half a table. The first answer that is not a table decides the result (404: a token user). */
    override suspend fun permissionsOf(user: String): AccessResult<UserPermissions> {
        return read(requests.userPermissions(user), ::parsePermissionRows, notFoundIsResult = true).andThen { rows ->
            read(requests.userTopicPermissions(user), ::parseTopicRows, notFoundIsResult = true).andThen { topics ->
                AccessResult.Loaded(UserPermissions(user, rows, topics))
            }
        }
    }

    /** Continues with the value of a loaded result and passes every other answer on unchanged: a `when` over the sealed type, so no branch can throw. */
    private suspend fun <T, R> AccessResult<T>.andThen(next: suspend (T) -> AccessResult<R>): AccessResult<R> = when (this) {
        is AccessResult.Loaded -> next(value)
        AccessResult.NoManagementTag -> AccessResult.NoManagementTag
        AccessResult.NotAuthorised -> AccessResult.NotAuthorised
        AccessResult.Unreachable -> AccessResult.Unreachable
        is AccessResult.Unexpected -> AccessResult.Unexpected(reason)
        AccessResult.NotFound -> AccessResult.NotFound
        AccessResult.NotOffered -> AccessResult.NotOffered
    }

    private suspend fun <T> read(url: String, parse: (JsonElement, String?) -> T?, notFoundIsResult: Boolean = false): AccessResult<T> {
        if (isMigrationSourceOnly(brokerVersion)) return AccessResult.NotOffered
        return when (val reply = http.get(url)) {
            ManagementReply.Unreachable, ManagementReply.CertificateRejected, ManagementReply.NoTlsAnswer -> AccessResult.Unreachable
            is ManagementReply.Response -> when (reply.status) {
                200 -> parseBody(reply.body, parse)
                404 -> if (notFoundIsResult) AccessResult.NotFound else AccessResult.Unexpected("HTTP 404")
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

    // Names only: every other field of a user (password_hash, tags, limits) is dropped here and never enters a model.
    private fun parseUsers(root: JsonElement, secret: String?): UserNames? {
        val names = (root as? JsonArray)?.map { text((it as? JsonObject)?.get("name")) ?: return null } ?: return null
        return UserNames(SecretGuard.redactAll(names, secret).values)
    }

    private fun parsePermissionRows(root: JsonElement, secret: String?): List<PermissionRow>? =
        (root as? JsonArray)?.map { element ->
            val obj = element as? JsonObject ?: return null
            PermissionRow(
                vhost = SecretGuard.redact(text(obj["vhost"]) ?: return null, secret).text,
                configure = cell(obj["configure"], secret) ?: return null,
                write = cell(obj["write"], secret) ?: return null,
                read = cell(obj["read"], secret) ?: return null,
            )
        }

    private fun parseTopicRows(root: JsonElement, secret: String?): List<TopicPermissionRow>? =
        (root as? JsonArray)?.map { element ->
            val obj = element as? JsonObject ?: return null
            TopicPermissionRow(
                vhost = SecretGuard.redact(text(obj["vhost"]) ?: return null, secret).text,
                exchange = SecretGuard.redact(text(obj["exchange"]) ?: return null, secret).text,
                write = cell(obj["write"], secret) ?: return null,
                read = cell(obj["read"], secret) ?: return null,
            )
        }

    // The raw text stays; the empty string is "no permission", everything else (`^$` included) is a pattern. A hidden pattern is never a regular expression.
    private fun cell(element: JsonElement?, secret: String?): PatternCell? {
        val raw = text(element) ?: return null
        val guarded = SecretGuard.redact(raw, secret)
        return when {
            guarded.hidden -> PatternCell(guarded.text, PatternReading.HasPattern(guarded.text), hidden = true)
            raw.isEmpty() -> PatternCell("", PatternReading.NoPermission)
            else -> PatternCell(raw, PatternReading.HasPattern(raw))
        }
    }

    private fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        const val MAX_JSON_DEPTH = 64
        const val NOT_UNDERSTOOD = "The answer was not understood."
    }
}

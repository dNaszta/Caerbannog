package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.UntrustedJson
import dev.caerbannog.core.json.parseUntrustedJson
import dev.caerbannog.core.migrate3x.ReadOutcome
import dev.caerbannog.core.migrate3x.ThreeXReads
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** The most queue pages one read of a vhost may take (R1): 100 x 500 = 50 000 queues, ten times what was measured. */
internal const val MAX_QUEUE_PAGES: Int = 100

private const val MAX_JSON_DEPTH = 64
private const val STATUS_OK = 200
private const val STATUS_UNAUTHORISED = 401
private const val STATUS_FORBIDDEN = 403
private const val UNREADABLE = "The broker answered in a form that could not be read"
private const val TOO_LONG = "The queue list is too long to read"

/**
 * The GET calls of a live 3.x source (M8-02b). GET-only: this class must only ever call [ManagementHttp.get]. Every failure is a
 * [ReadOutcome] with a FIXED text: no body, URL or header ever goes into a reason, and nothing is logged.
 */
internal class ManagementThreeXReads(
    private val http: ManagementHttp,
    private val requests: ManagementRequests,
) : ThreeXReads {

    override suspend fun definitions(): ReadOutcome<String> = fetch(requests.definitions()) { ReadOutcome.Ok(it) }

    override suspend fun deprecatedFeaturesInUse(): ReadOutcome<Set<String>> = fetch(requests.deprecatedFeaturesUsed()) { body ->
        val list = parse(body) as? JsonArray ?: return@fetch failed()
        val names = list.map { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return@fetch failed() }
        ReadOutcome.Ok(names.toSet())
    }

    override suspend fun storageVersions(vhost: String): ReadOutcome<Map<String, Int?>> {
        val result = mutableMapOf<String, Int?>()
        var page = 1
        var pageCount = 1
        while (page <= pageCount) {
            val outcome = fetch(requests.queuesWithStorageVersion(vhost, page)) { body -> readPage(body, page, result) }
            when (outcome) {
                is ReadOutcome.Ok -> if (page == 1) pageCount = outcome.value
                is ReadOutcome.Failed -> return outcome
                ReadOutcome.NotPermitted -> return ReadOutcome.NotPermitted
                ReadOutcome.Unreachable -> return ReadOutcome.Unreachable
            }
            page++
        }
        return ReadOutcome.Ok(result)
    }

    /** Adds the queues of one page to [into] and returns the announced page count (only page 1 counts; later pages are ignored). */
    private fun readPage(body: String, page: Int, into: MutableMap<String, Int?>): ReadOutcome<Int> {
        val root = parse(body) as? JsonObject ?: return failed()
        val items = root["items"] as? JsonArray ?: return failed()
        var count = 1
        if (page == 1) {
            count = pageCount(root["page_count"]) ?: return failed()
            if (count > MAX_QUEUE_PAGES) return ReadOutcome.Failed(TOO_LONG)
        }
        for (item in items) {
            val queue = item as? JsonObject ?: return failed()
            val name = (queue["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return failed()
            into[name] = (queue["storage_version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        }
        return ReadOutcome.Ok(count)
    }

    /** Missing is one page; present must be a whole number of at least 0. */
    private fun pageCount(element: JsonElement?): Int? {
        if (element == null) return 1
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull || primitive.isString) return null
        val number = primitive.intOrNull ?: return null
        return if (number < 0) null else maxOf(number, 1)
    }

    private fun parse(body: String): JsonElement? = (parseUntrustedJson(body, MAX_JSON_DEPTH) as? UntrustedJson.Parsed)?.element

    private fun failed(): ReadOutcome.Failed = ReadOutcome.Failed(UNREADABLE)

    private suspend fun <T> fetch(url: String, onOk: (String) -> ReadOutcome<T>): ReadOutcome<T> =
        when (val reply = http.get(url)) {
            is ManagementReply.Response -> when (reply.status) {
                STATUS_OK -> onOk(reply.body)
                STATUS_UNAUTHORISED, STATUS_FORBIDDEN -> ReadOutcome.NotPermitted
                else -> failed()
            }
            ManagementReply.CertificateRejected, ManagementReply.NoTlsAnswer, ManagementReply.Unreachable -> ReadOutcome.Unreachable
        }
}

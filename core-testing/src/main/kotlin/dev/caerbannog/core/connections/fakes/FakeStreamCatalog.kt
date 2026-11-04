package dev.caerbannog.core.connections.fakes

import dev.caerbannog.core.streams.Capabilities
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.ConsumerRow
import dev.caerbannog.core.streams.PluginStatus
import dev.caerbannog.core.streams.PublisherRow
import dev.caerbannog.core.streams.Retention
import dev.caerbannog.core.streams.StreamCatalog
import dev.caerbannog.core.streams.StreamListRequest
import dev.caerbannog.core.streams.StreamPage
import dev.caerbannog.core.streams.StreamSummary
import dev.caerbannog.core.streams.Visibility
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * M2-03 part 2 test double for [StreamCatalog]. Lives in `core-testing`, shared by the `core` and `acceptance-tests`
 * tests (decision recorded in docs/tasks/M2-03-stream-gateway.md, part 2 step 2). It is built from
 * domain objects, not from JSON: `core` has no JSON library, the recorded bodies are parsed in `data-rabbit`
 * tests (ManagementJsonTest) into the very values [RecordedStreams] uses here.
 *
 * Honest by design, each rule is a measured fact of the real management API (spec "Management API facts"):
 *  - the list is per vhost like `GET /api/queues/{vhost}`: other vhosts' queues are neither listed nor counted;
 *  - the broker pages over ALL queues of the vhost, then the client keeps the streams: a page can hold fewer streams than
 *    `pageSize`, and the pagination numbers describe the broker's pages, not the streams;
 *  - `pageSize` 1..500 and a page inside 1..pageCount (an empty broker allows page 1); anything else is [CatalogResult.BadPaging];
 *  - the `name` filter is a substring, or a regex with `useRegex`; an invalid regex is no error and matches nothing;
 *  - no management tag: everything is [CatalogResult.NotAuthorised], and capabilities say visibility NONE and plugin UNKNOWN;
 *  - plugin missing: publishers and consumers are [CatalogResult.PluginMissing];
 *  - a missing stream is [CatalogResult.NotFound].
 *  - M2-04: a name is unique in a vhost, so adding a stream or queue again REPLACES the old one (a test updates statistics that way);
 *    `publisherCounts` groups the publisher rows of the vhost by stream, streams without a publisher are absent.
 * Every call is counted in [calls], so a test can show that something did NOT call the broker. A test can hold the next call
 * of a name ([holdNext]) to complete it late, and make one page of the list fail ([failListPage]).
 */
class FakeStreamCatalog : StreamCatalog {

    private data class Entry(val name: String, val vhost: String, val type: String, val summary: StreamSummary?)

    private val entries = Collections.synchronizedList(ArrayList<Entry>())
    private val publisherRows = Collections.synchronizedMap(HashMap<Pair<String, String>, List<PublisherRow>>())
    private val consumerRows = Collections.synchronizedMap(HashMap<Pair<String, String>, List<ConsumerRow>>())

    /** What the user's tags allow. NONE answers every call with NotAuthorised (HTTP 401 "Not management user"). */
    @Volatile var visibility: Visibility = Visibility.FULL

    /** False: the broker lacks `rabbitmq_stream_management` (404 on the top-level publishers path). */
    @Volatile var pluginPresent: Boolean = true

    /** True: every call answers [CatalogResult.Unreachable]. */
    @Volatile var unreachable: Boolean = false

    /** Names of the calls in order: "listStreams", "streamDetail", "publishers", "publisherCounts", "consumers", "capabilities". */
    val calls: MutableList<String> = Collections.synchronizedList(ArrayList())

    private val holds = ConcurrentHashMap<String, CallGate>()
    private val pageFailures = ConcurrentHashMap<Int, CatalogResult.Failure>()

    /** The next call named [call] (see [calls]) records itself, computes its answer, then waits until the gate is released. One-shot. */
    fun holdNext(call: String, ignoreCancellation: Boolean = false): CallGate =
        CallGate(ignoreCancellation).also { holds[call] = it }

    /** `listStreams` for page [page] answers [failure] (until [clearFailures]); other pages work. */
    fun failListPage(page: Int, failure: CatalogResult.Failure) {
        pageFailures[page] = failure
    }

    fun clearFailures() = pageFailures.clear()

    private suspend fun <T> late(call: String, answer: T): T {
        holds.remove(call)?.await()
        return answer
    }

    private fun put(entry: Entry) {
        synchronized(entries) {
            entries.removeAll { it.vhost == entry.vhost && it.name == entry.name }
            entries += entry
        }
    }

    fun addStream(summary: StreamSummary): FakeStreamCatalog = apply {
        put(Entry(summary.name, summary.vhost, "stream", summary))
    }

    /** A queue that is not a stream (quorum, classic): listed by the broker, never returned by the catalog. */
    fun addQueue(name: String, vhost: String = "/", type: String = "quorum"): FakeStreamCatalog = apply {
        put(Entry(name, vhost, type, null))
    }

    fun setPublishers(vhost: String, stream: String, rows: List<PublisherRow>) {
        publisherRows[vhost to stream] = rows
    }

    fun setConsumers(vhost: String, stream: String, rows: List<ConsumerRow>) {
        consumerRows[vhost to stream] = rows
    }

    private fun refusal(call: String): CatalogResult.Failure? {
        calls += call
        return when {
            unreachable -> CatalogResult.Unreachable
            visibility == Visibility.NONE -> CatalogResult.NotAuthorised
            else -> null
        }
    }

    override suspend fun listStreams(request: StreamListRequest): CatalogResult<StreamPage> =
        late("listStreams", computeList(request))

    private fun computeList(request: StreamListRequest): CatalogResult<StreamPage> {
        refusal("listStreams")?.let { return it }
        pageFailures[request.page]?.let { return it }
        if (request.pageSize !in 1..MAX_PAGE_SIZE || request.page < 1) return CatalogResult.BadPaging
        val matcher = nameMatcher(request)
        val matching = synchronized(entries) { entries.filter { it.vhost == request.vhost && matcher(it.name) } }
        val pageCount = (matching.size + request.pageSize - 1) / request.pageSize
        if (request.page > maxOf(pageCount, 1)) return CatalogResult.BadPaging
        val items = matching.drop((request.page - 1) * request.pageSize).take(request.pageSize)
        return CatalogResult.Success(
            StreamPage(
                rows = items.mapNotNull { it.summary },
                page = request.page,
                pageCount = pageCount,
                pageSize = request.pageSize,
                filteredCount = matching.size,
                itemCount = items.size,
                totalCount = synchronized(entries) { entries.count { it.vhost == request.vhost } },
            ),
        )
    }

    private fun nameMatcher(request: StreamListRequest): (String) -> Boolean {
        val filter = request.nameFilter?.takeIf { it.isNotEmpty() } ?: return { true }
        if (!request.useRegex) return { it.contains(filter) }
        val regex = try {
            Regex(filter)
        } catch (e: IllegalArgumentException) {
            return { false } // the broker answers 200 and no match for an invalid regex
        }
        return { regex.containsMatchIn(it) }
    }

    override suspend fun streamDetail(vhost: String, name: String): CatalogResult<StreamSummary> {
        refusal("streamDetail")?.let { return it }
        val found = synchronized(entries) { entries.firstOrNull { it.vhost == vhost && it.name == name }?.summary }
        return found?.let { CatalogResult.Success(it) } ?: CatalogResult.NotFound
    }

    override suspend fun publishers(vhost: String, stream: String): CatalogResult<List<PublisherRow>> {
        refusal("publishers")?.let { return it }
        if (!pluginPresent) return CatalogResult.PluginMissing
        return CatalogResult.Success(publisherRows[vhost to stream].orEmpty())
    }

    override suspend fun publisherCounts(vhost: String): CatalogResult<Map<String, Int>> {
        val answer: CatalogResult<Map<String, Int>> = refusal("publisherCounts")
            ?: if (!pluginPresent) {
                CatalogResult.PluginMissing
            } else {
                val rows = synchronized(publisherRows) { publisherRows.entries.toList() }
                CatalogResult.Success(rows.filter { it.key.first == vhost && it.value.isNotEmpty() }.associate { it.key.second to it.value.size })
            }
        return late("publisherCounts", answer)
    }

    override suspend fun consumers(vhost: String, stream: String): CatalogResult<List<ConsumerRow>> {
        refusal("consumers")?.let { return it }
        if (!pluginPresent) return CatalogResult.PluginMissing
        return CatalogResult.Success(consumerRows[vhost to stream].orEmpty())
    }

    override suspend fun capabilities(): CatalogResult<Capabilities> {
        calls += "capabilities"
        if (unreachable) return CatalogResult.Unreachable
        val plugin = when {
            visibility == Visibility.NONE -> PluginStatus.UNKNOWN // the detection call is a 401 for a user without the tag
            pluginPresent -> PluginStatus.PRESENT
            else -> PluginStatus.MISSING
        }
        return CatalogResult.Success(Capabilities(streamPlugin = plugin, visibility = visibility))
    }

    companion object {
        const val MAX_PAGE_SIZE = 500

        /** A stream row with the values of the recorded fixtures (`queues-page-one-stream.json`) unless overridden. */
        fun stream(
            name: String,
            vhost: String = "/",
            messages: Long? = 3,
            consumers: Int = 0,
            retention: Retention = Retention(),
        ) = StreamSummary(
            name = name, vhost = vhost, messages = messages, consumers = consumers, segments = 1,
            firstTimestamp = 1_793_799_253_178, leader = "rabbit@node1", node = "rabbit@node1",
            members = listOf("rabbit@node1"), online = listOf("rabbit@node1"), retention = retention,
        )
    }
}

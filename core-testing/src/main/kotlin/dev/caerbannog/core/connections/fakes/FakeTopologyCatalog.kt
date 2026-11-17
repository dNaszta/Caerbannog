package dev.caerbannog.core.connections.fakes

import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.topology.PolicyRow
import dev.caerbannog.core.topology.QueueDeclaration
import dev.caerbannog.core.topology.QueuePage
import dev.caerbannog.core.topology.QueueRow
import dev.caerbannog.core.topology.TopologyCatalog
import dev.caerbannog.core.topology.VhostRow
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * M2-08a test double for [TopologyCatalog], built from domain objects like [FakeStreamCatalog]. Honest by design, each rule is a measured
 * fact of the management API (brief M2-08 spikes S-A to S-C, fixtures `data-rabbit/.../fixtures/m2-08/`):
 *  - per vhost: other vhosts' queues, exchanges and bindings are never returned; a vhost nobody declared is [CatalogResult.NotFound]
 *    (the broker answers 404 for a vhost the user has no permission on or that does not exist); "/" exists from the start;
 *  - the broker pages over ALL queues of the vhost, [TopologyCatalog.QUEUE_PAGE_SIZE] per page, name ascending; streams are among those queues
 *    (they count in `totalCount` and fill pages) but are never in `rows`, so a page can hold fewer rows than the page size, or none;
 *  - page below 1, or past the last page (an empty vhost still has a page 1), is [CatalogResult.BadPaging];
 *  - no management tag ([hasManagementTag] false): every call is [CatalogResult.NotAuthorised] (401 "Not management user"), also for a
 *    vhost that does not exist; the user's configure/write/read permissions do NOT hide anything (measured: an empty-permission user with the tag sees everything);
 *  - exchanges always carry the default exchange `""` (direct, durable) first; bindings always carry the implicit binding of the default
 *    exchange for EVERY queue of the vhost, streams included (routing key = queue name), before the declared bindings;
 *  - M2-08a round 2 (spike S-E): [vhosts] lists the declared vhosts the user has a permission on ([permittedVhosts], null = all); with
 *    [listsEveryVhost] (a `monitoring` or administrator user) it lists EVERY declared vhost, and a listed vhost the user has no permission
 *    on still answers NotFound on the queue, exchange, binding and detail calls (404, measured); no tag is NotAuthorised;
 *  - a missing queue, or a stream asked for as a queue, is NotFound on [queueDetail]; names are unique per vhost (adding again REPLACES).
 *  - M9-14 (spike `docs/tasks/M9-14-spike-results.md`): [queueDeclarations] answers EVERY queue of the vhost, streams included, with the broker's flags and
 *    typed arguments, name ascending, unpaged (the real catalog pages over 500 per page itself); a queue added with [addQueue] has the declaration a plain
 *    durable queue has (no arguments) unless [declare] gives it another. [policies] and [operatorPolicies] are two separate lists (the broker keeps operator
 *    policies out of `/api/policies`), every user with a permission on the vhost may read both (measured: management, monitoring and policymaker alike), a
 *    vhost nobody declared is NotFound (404, also what a user with no permission on it gets).
 * Not modelled: statistics lag (a test sets the counts it wants), policy matching (a test sets [QueueRow.policy]), a real cluster's offline member
 * (a test builds a row with a shorter `online`; S-D: not measurable on a single node).
 * Every call is counted in [calls]; a test can hold the next call ([holdNext]) and make one queue page fail ([failQueuePage]) or a call
 * ([failCall]).
 */
class FakeTopologyCatalog : TopologyCatalog {

    private data class QueueEntry(val name: String, val vhost: String, val type: String, val row: QueueRow?)

    private val queueEntries = Collections.synchronizedList(ArrayList<QueueEntry>())
    private val exchangeRows = Collections.synchronizedList(ArrayList<ExchangeRow>())
    private val declarations = Collections.synchronizedList(ArrayList<QueueDeclaration>())
    private val policyRows = Collections.synchronizedList(ArrayList<PolicyRow>())
    private val operatorPolicyRows = Collections.synchronizedList(ArrayList<PolicyRow>())
    private val bindingRows = Collections.synchronizedList(ArrayList<BindingRow>())
    private val vhosts: MutableSet<String> = Collections.synchronizedSet(hashSetOf("/"))

    /** False: the user has no management tag, every call is NotAuthorised. */
    @Volatile var hasManagementTag: Boolean = true

    /** M2-08a round 2: the vhosts the user has a permission on; null means every declared vhost. Others answer NotFound on the vhost calls. */
    @Volatile var permittedVhosts: Set<String>? = null

    /** M2-08a round 2: true for a `monitoring` or administrator user: `vhosts()` lists every declared vhost, readable or not (S-E). */
    @Volatile var listsEveryVhost: Boolean = false

    /** True: every call answers [CatalogResult.Unreachable]. */
    @Volatile var unreachable: Boolean = false

    /** Names of the calls in order: "vhosts", "queues", "exchanges", "bindings", "queueDetail", "queueDeclarations", "policies", "operatorPolicies". */
    val calls: MutableList<String> = Collections.synchronizedList(ArrayList())

    /**
     * M2-08b: every call with its arguments, in order: `vhosts`, `queues /|1`, `exchanges /`, `bindings /`, `queueDetail /|name`
     * (the vhost as given, `|` before the page or name). Lets a test say WHICH vhost and which page the app asked for.
     */
    val requests: MutableList<String> = Collections.synchronizedList(ArrayList())

    private val holds = ConcurrentHashMap<String, CallGate>()
    private val pageFailures = ConcurrentHashMap<Int, CatalogResult.Failure>()
    private val callFailures = ConcurrentHashMap<String, CatalogResult.Failure>()

    /** The next call named [call] records itself, computes its answer, then waits until the gate is released. One-shot. */
    fun holdNext(call: String, ignoreCancellation: Boolean = false): CallGate =
        CallGate(ignoreCancellation).also { holds[call] = it }

    /** `queues` for page [page] answers [failure] (until [clearFailures]); other pages work. */
    fun failQueuePage(page: Int, failure: CatalogResult.Failure) {
        pageFailures[page] = failure
    }

    /** Every call named [call] answers [failure] (default: a body this app does not understand) until [clearFailures]. */
    fun failCall(call: String, failure: CatalogResult.Failure = CatalogResult.Unexpected) {
        callFailures[call] = failure
    }

    fun clearFailures() {
        pageFailures.clear()
        callFailures.clear()
    }

    /** A vhost that exists and has nothing in it yet. */
    fun declareVhost(name: String): FakeTopologyCatalog = apply { vhosts += name }

    /** A queue row; adding a queue of the same name and vhost again replaces it (a test updates statistics that way). */
    fun addQueue(row: QueueRow): FakeTopologyCatalog = apply {
        vhosts += row.vhost
        put(QueueEntry(row.name, row.vhost, row.type, if (row.type == "stream") null else row))
        putDeclaration(QueueDeclaration(row.name, row.vhost, row.type))
    }

    /** M9-14: what the broker stores for one queue or stream (flags, typed arguments). Replaces the declaration of the same name; a stream is not listed by [queues]. */
    fun declare(declaration: QueueDeclaration): FakeTopologyCatalog = apply {
        vhosts += declaration.vhost
        if (declaration.type != "stream") {
            put(QueueEntry(declaration.name, declaration.vhost, declaration.type, QueueRow(declaration.name, declaration.vhost, declaration.type, 0, 0, 0, null, emptyList(), emptyList(), null, "running")))
        } else {
            put(QueueEntry(declaration.name, declaration.vhost, "stream", null))
        }
        putDeclaration(declaration)
    }

    /** M9-14: a policy (`GET /api/policies/{vhost}`); the same name in the same vhost replaces it. */
    fun addPolicy(row: PolicyRow): FakeTopologyCatalog = apply {
        vhosts += row.vhost
        synchronized(policyRows) { policyRows.removeAll { it.vhost == row.vhost && it.name == row.name }; policyRows += row }
    }

    /** M9-14: an operator policy (`GET /api/operator-policies/{vhost}`), a list of its own. */
    fun addOperatorPolicy(row: PolicyRow): FakeTopologyCatalog = apply {
        vhosts += row.vhost
        synchronized(operatorPolicyRows) { operatorPolicyRows.removeAll { it.vhost == row.vhost && it.name == row.name }; operatorPolicyRows += row }
    }

    /**
     * M9-09: a queue as the broker lists it AND declares it, in one call: the statistics row (type, policy) from [row] and the flags and typed arguments from
     * [declaration] (a real broker returns both views of one queue; [addQueue] and [declare] alone each replace the other's half). Both must name the same queue.
     */
    fun addQueue(row: QueueRow, declaration: QueueDeclaration): FakeTopologyCatalog = apply {
        require(row.name == declaration.name && row.vhost == declaration.vhost && row.type == declaration.type) { "the row and the declaration are of two queues" }
        vhosts += row.vhost
        put(QueueEntry(row.name, row.vhost, row.type, row))
        putDeclaration(declaration)
    }

    private fun putDeclaration(declaration: QueueDeclaration) {
        synchronized(declarations) {
            declarations.removeAll { it.vhost == declaration.vhost && it.name == declaration.name }
            declarations += declaration
        }
    }

    /** A stream: listed by the broker among the queues (and bound implicitly), never returned by this catalog. */
    fun addStream(name: String, vhost: String = "/", arguments: Map<String, ArgumentValue> = emptyMap()): FakeTopologyCatalog = apply {
        vhosts += vhost
        put(QueueEntry(name, vhost, "stream", null))
        putDeclaration(QueueDeclaration(name, vhost, "stream", arguments = arguments))
    }

    fun deleteQueue(name: String, vhost: String = "/") {
        synchronized(queueEntries) { queueEntries.removeAll { it.vhost == vhost && it.name == name } }
        synchronized(declarations) { declarations.removeAll { it.vhost == vhost && it.name == name } }
    }

    /** The queue as the fake holds it now (test accessor, not a broker call: it is not recorded). Null when missing or a stream. */
    fun queueRow(name: String, vhost: String = "/"): QueueRow? = synchronized(queueEntries) { queueEntries.firstOrNull { it.vhost == vhost && it.name == name }?.row }

    fun addExchange(row: ExchangeRow): FakeTopologyCatalog = apply {
        vhosts += row.vhost
        synchronized(exchangeRows) {
            exchangeRows.removeAll { it.vhost == row.vhost && it.name == row.name }
            exchangeRows += row
        }
    }

    fun deleteExchange(name: String, vhost: String = "/") {
        synchronized(exchangeRows) { exchangeRows.removeAll { it.vhost == vhost && it.name == name } }
    }

    /** A declared binding (not the implicit default-exchange ones, those are always there). */
    fun addBinding(row: BindingRow): FakeTopologyCatalog = apply {
        vhosts += row.vhost
        bindingRows += row
    }

    fun deleteBinding(exchange: String, queue: String, routingKey: String = "", vhost: String = "/") {
        synchronized(bindingRows) {
            bindingRows.removeAll {
                it.vhost == vhost && it.source == exchange && it.destination == queue && it.routingKey == routingKey
            }
        }
    }

    private fun put(entry: QueueEntry) {
        synchronized(queueEntries) {
            queueEntries.removeAll { it.vhost == entry.vhost && it.name == entry.name }
            queueEntries += entry
        }
    }

    // The tag is checked before the vhost: a user without the tag gets 401 for a missing vhost too.
    private fun refusal(call: String, vhost: String?, detail: String = ""): CatalogResult.Failure? {
        calls += call
        requests += listOfNotNull(call, vhost).joinToString(" ") + detail
        return when {
            unreachable -> CatalogResult.Unreachable
            !hasManagementTag -> CatalogResult.NotAuthorised
            vhost != null && (vhost !in vhosts || permittedVhosts?.let { vhost !in it } == true) -> CatalogResult.NotFound
            else -> callFailures[call]
        }
    }

    private suspend fun <T> late(call: String, answer: T): T {
        holds.remove(call)?.await()
        return answer
    }

    override suspend fun vhosts(): CatalogResult<List<VhostRow>> {
        val answer: CatalogResult<List<VhostRow>> = refusal("vhosts", null) ?: run {
            val declared = synchronized(vhosts) { vhosts.toList() }
            val listed = if (listsEveryVhost) declared else declared.filter { permittedVhosts?.contains(it) ?: true }
            CatalogResult.Success(listed.sorted().map { VhostRow(it) })
        }
        return late("vhosts", answer)
    }

    override suspend fun queues(vhost: String, page: Int): CatalogResult<QueuePage> = late("queues", computeQueues(vhost, page))

    private fun computeQueues(vhost: String, page: Int): CatalogResult<QueuePage> {
        refusal("queues", vhost, "|$page")?.let { return it }
        pageFailures[page]?.let { return it }
        val all = synchronized(queueEntries) { queueEntries.filter { it.vhost == vhost } }.sortedBy { it.name }
        val size = TopologyCatalog.QUEUE_PAGE_SIZE
        val pageCount = (all.size + size - 1) / size
        if (page < 1 || page > maxOf(pageCount, 1)) return CatalogResult.BadPaging
        val items = all.drop((page - 1) * size).take(size)
        return CatalogResult.Success(QueuePage(items.mapNotNull { it.row }, page, pageCount, size, all.size))
    }

    override suspend fun exchanges(vhost: String): CatalogResult<List<ExchangeRow>> {
        val answer: CatalogResult<List<ExchangeRow>> = refusal("exchanges", vhost)
            ?: CatalogResult.Success(
                listOf(ExchangeRow("", vhost, "direct", durable = true, autoDelete = false, internal = false)) +
                    synchronized(exchangeRows) { exchangeRows.filter { it.vhost == vhost && it.name.isNotEmpty() } },
            )
        return late("exchanges", answer)
    }

    override suspend fun bindings(vhost: String): CatalogResult<List<BindingRow>> {
        val answer: CatalogResult<List<BindingRow>> = refusal("bindings", vhost) ?: run {
            val implicit = synchronized(queueEntries) { queueEntries.filter { it.vhost == vhost } }.sortedBy { it.name }
                .map { BindingRow("", vhost, it.name, BindingDestinationType.QUEUE, it.name) }
            CatalogResult.Success(implicit + synchronized(bindingRows) { bindingRows.filter { it.vhost == vhost } })
        }
        return late("bindings", answer)
    }

    override suspend fun queueDeclarations(vhost: String): CatalogResult<List<QueueDeclaration>> {
        val answer: CatalogResult<List<QueueDeclaration>> = refusal("queueDeclarations", vhost)
            ?: CatalogResult.Success(synchronized(declarations) { declarations.filter { it.vhost == vhost } }.sortedBy { it.name })
        return late("queueDeclarations", answer)
    }

    override suspend fun policies(vhost: String): CatalogResult<List<PolicyRow>> {
        val answer: CatalogResult<List<PolicyRow>> = refusal("policies", vhost)
            ?: CatalogResult.Success(synchronized(policyRows) { policyRows.filter { it.vhost == vhost } })
        return late("policies", answer)
    }

    override suspend fun operatorPolicies(vhost: String): CatalogResult<List<PolicyRow>> {
        val answer: CatalogResult<List<PolicyRow>> = refusal("operatorPolicies", vhost)
            ?: CatalogResult.Success(synchronized(operatorPolicyRows) { operatorPolicyRows.filter { it.vhost == vhost } })
        return late("operatorPolicies", answer)
    }

    override suspend fun queueDetail(vhost: String, name: String): CatalogResult<QueueRow> {
        refusal("queueDetail", vhost, "|$name")?.let { return it }
        val found = synchronized(queueEntries) { queueEntries.firstOrNull { it.vhost == vhost && it.name == name }?.row }
        return late("queueDetail", found?.let { CatalogResult.Success(it) } ?: CatalogResult.NotFound)
    }

    companion object {
        /** A quorum queue with the values of the recorded fixtures (`queues-quorum-classic-stream-full.json`) unless overridden; one member, online. */
        fun quorum(
            name: String,
            vhost: String = "/",
            ready: Long? = 0,
            unacked: Long? = 0,
            consumers: Int? = 0,
            members: List<String> = listOf("rabbit@node1"),
            online: List<String> = members,
            leader: String? = members.firstOrNull(),
            policy: String? = null,
            state: String? = "running",
        ) = QueueRow(name, vhost, "quorum", ready, unacked, consumers, leader, members, online, policy, state)

        /** A classic queue: no leader, members or online list (recorded). */
        fun classic(
            name: String,
            vhost: String = "/",
            ready: Long? = 0,
            unacked: Long? = 0,
            consumers: Int? = 0,
            policy: String? = null,
            state: String? = "running",
        ) = QueueRow(name, vhost, "classic", ready, unacked, consumers, null, emptyList(), emptyList(), policy, state)

        fun exchange(
            name: String,
            type: String = "direct",
            vhost: String = "/",
            arguments: Map<String, String> = emptyMap(),
            internal: Boolean = false,
            superStream: Boolean = false,
            typedArguments: Map<String, ArgumentValue> = emptyMap(),
        ) = ExchangeRow(name, vhost, type, durable = true, autoDelete = false, internal = internal, arguments = arguments, superStream = superStream, typedArguments = typedArguments)

        fun binding(
            source: String,
            destination: String,
            routingKey: String = "",
            destinationType: BindingDestinationType = BindingDestinationType.QUEUE,
            vhost: String = "/",
            arguments: Map<String, String> = emptyMap(),
            typedArguments: Map<String, ArgumentValue> = emptyMap(),
        ) = BindingRow(source, vhost, destination, destinationType, routingKey, arguments, typedArguments)
    }
}

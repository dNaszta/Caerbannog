package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.connections.brokerMajorVersion
import dev.caerbannog.core.connections.displayableBrokerVersion
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** GET-only by shape: no method here can change anything. Implemented by data-rabbit (M8-02b) and by FakeThreeXReads. */
interface ThreeXReads {
    suspend fun definitions(): ReadOutcome<String>
    suspend fun storageVersions(vhost: String): ReadOutcome<Map<String, Int?>>
    suspend fun deprecatedFeaturesInUse(): ReadOutcome<Set<String>>
}

sealed interface ReadOutcome<out T> {
    data class Ok<T>(val value: T) : ReadOutcome<T>
    data object NotPermitted : ReadOutcome<Nothing>
    data object Unreachable : ReadOutcome<Nothing>
    data class Failed(val reason: String) : ReadOutcome<Nothing>
}

interface DefinitionsSource {
    /**
     * The connection this source reads; [LiveAnalysisOutcome.Unreachable] and the "<name> could not be reached" text need it when no fetch
     * succeeded (rulings 1).
     */
    val connectionName: String

    suspend fun fetch(): FetchResult
}

sealed interface FetchResult {
    data class Fetched(val document: DefinitionsDocument, val facts: LiveSourceFacts) : FetchResult
    data class NotPermitted(val reason: String) : FetchResult
    data object Unreachable : FetchResult
    data class Failed(val reason: String) : FetchResult
}

/** Fixed texts of the live source. Never a body, a URL or a secret. */
object LiveSourceTexts {
    const val VERSION_UNREADABLE = "The broker version could not be read, so it cannot be analysed"
    const val NOT_THREE_X = "Only a RabbitMQ 3.x connection can be analysed live"
    const val CANNOT_EXPORT = "This user cannot export definitions: the administrator tag is needed"

    /** Fixed text for a `ReadOutcome.Failed` of the definitions call; the adapter's own reason is never passed on. */
    const val READ_FAILED = "The broker could not return its definitions"

    /** M8-02a (test developer's proposal, the brief says "fixed too large text"). */
    const val EXPORT_TOO_LARGE = "The definitions export of this broker is too large to analyse"

    /** M8-02a (test developer's proposal, the brief says "fixed text"): not parsable, or a per-virtual-host export. */
    const val EXPORT_UNUSABLE = "The broker did not return a usable definitions export"

    fun unreachable(connectionName: String): String = "$connectionName could not be reached"
}

const val STORAGE_VERSION_READS: Int = 6
const val STORAGE_VERSION_PAUSE_MILLIS: Long = 1_000L

/**
 * Reads a live 3.x connection into a definitions document and facts, with GET-only [ThreeXReads]. The failure order, the bound of the
 * storage version scan and "a deprecated feature list never fails the fetch" are product behaviour, so they live here and not in the
 * HTTP adapter (testable without a broker). [pause] is injected: core never sleeps.
 */
class LiveDefinitionsSource(
    override val connectionName: String,
    private val brokerVersion: String?,
    private val reads: ThreeXReads,
    private val pause: suspend (millis: Long) -> Unit,
) : DefinitionsSource {
    override suspend fun fetch(): FetchResult {
        // F5: the source applies the display rule itself, so a caller cannot hand it an odd text.
        val version = displayableBrokerVersion(brokerVersion)
        val major = brokerMajorVersion(version)
        if (major == null) return FetchResult.Failed(LiveSourceTexts.VERSION_UNREADABLE)
        if (major >= FIRST_NON_SOURCE_MAJOR) return FetchResult.Failed(LiveSourceTexts.NOT_THREE_X)

        val text = when (val outcome = reads.definitions()) {
            is ReadOutcome.Ok -> outcome.value
            ReadOutcome.NotPermitted -> return FetchResult.NotPermitted(LiveSourceTexts.CANNOT_EXPORT)
            ReadOutcome.Unreachable -> return FetchResult.Unreachable
            // The adapter's own reason is never passed on: no broker text anywhere.
            is ReadOutcome.Failed -> return FetchResult.Failed(LiveSourceTexts.READ_FAILED)
        }
        if (isTooLarge(text)) return FetchResult.Failed(LiveSourceTexts.EXPORT_TOO_LARGE)
        val document = when (val parsed = DefinitionsParser.parse(text)) {
            is DefinitionsParseResult.Parsed -> parsed.document
            // A live export always names its virtual hosts, so asking for one is as unusable as a rejection.
            is DefinitionsParseResult.NeedsVirtualHost, is DefinitionsParseResult.Rejected ->
                return FetchResult.Failed(LiveSourceTexts.EXPORT_UNUSABLE)
        }

        val deprecated = (reads.deprecatedFeaturesInUse() as? ReadOutcome.Ok)?.value.orEmpty()
        val versions = scanStorageVersions(document)
        return FetchResult.Fetched(document, LiveSourceFacts(connectionName, version, versions, deprecated))
    }

    // The UTF-8 size is at least the character count, so the cheap check settles the common cases before encoding.
    private fun isTooLarge(text: String): Boolean =
        text.length > MAX_DEFINITIONS_FILE_BYTES || text.encodeToByteArray().size > MAX_DEFINITIONS_FILE_BYTES

    private suspend fun scanStorageVersions(document: DefinitionsDocument): Map<QueueRef, ClassicIndexVersion> {
        val result = mutableMapOf<QueueRef, ClassicIndexVersion>()
        val classicByVhost = document.queues.filter { it.type == QueueKind.Classic }.groupBy({ it.vhost }, { it.name })
        for ((vhost, names) in classicByVhost) {
            scanVhost(vhost, names.toSet()).forEach { (name, version) -> result[QueueRef(vhost, name)] = version }
        }
        return result
    }

    // The storage version shows up a few seconds after a queue starts, so a missing value is read again, a bounded number of times.
    private suspend fun scanVhost(vhost: String, names: Set<String>): Map<String, ClassicIndexVersion> {
        val found = mutableMapOf<String, ClassicIndexVersion>()
        for (round in 1..STORAGE_VERSION_READS) {
            val pending = names - found.keys
            if (pending.isEmpty()) break
            // A failing read ends the scan of this vhost: its pending queues stay Unknown, the fetch never fails.
            val values = (reads.storageVersions(vhost) as? ReadOutcome.Ok)?.value ?: break
            for (name in pending) {
                val number = values[name] ?: continue
                found[name] = when (number) {
                    1 -> ClassicIndexVersion.V1
                    2 -> ClassicIndexVersion.V2
                    else -> ClassicIndexVersion.Unknown
                }
            }
            if ((names - found.keys).isNotEmpty() && round < STORAGE_VERSION_READS) pause(STORAGE_VERSION_PAUSE_MILLIS)
        }
        return names.associateWith { found[it] ?: ClassicIndexVersion.Unknown }
    }
}

private const val FIRST_NON_SOURCE_MAJOR = 4

/** The source of every connection that is not a readable 3.x one: refuses with a fixed text. The default of `StreamSource.definitionsSource`. */
class UnavailableDefinitionsSource(override val connectionName: String) : DefinitionsSource {
    override suspend fun fetch(): FetchResult = FetchResult.Failed(LiveSourceTexts.NOT_THREE_X)
}

/** Every read fails; never called, because [LiveDefinitionsSource] refuses an unknown or 4.x version before any read. */
object NoThreeXReads : ThreeXReads {
    override suspend fun definitions(): ReadOutcome<String> = ReadOutcome.Failed("no source")
    override suspend fun storageVersions(vhost: String): ReadOutcome<Map<String, Int?>> = ReadOutcome.Failed("no source")
    override suspend fun deprecatedFeaturesInUse(): ReadOutcome<Set<String>> = ReadOutcome.Failed("no source")
}

/** Proxy: a source of a closed or superseded connection reads nothing. */
internal class GuardedDefinitionsSource(private val inner: DefinitionsSource, private val isCurrent: () -> Boolean) : DefinitionsSource {
    override val connectionName: String get() = inner.connectionName
    override suspend fun fetch(): FetchResult = if (isCurrent()) inner.fetch() else FetchResult.Unreachable
}

/** Proxy: once the connection is superseded (also during a fetch) every read is Unreachable. */
internal class GuardedThreeXReads(private val inner: ThreeXReads, private val isCurrent: () -> Boolean) : ThreeXReads {
    private suspend fun <T> guarded(call: suspend () -> ReadOutcome<T>): ReadOutcome<T> = if (isCurrent()) call() else ReadOutcome.Unreachable

    override suspend fun definitions(): ReadOutcome<String> = guarded { inner.definitions() }
    override suspend fun storageVersions(vhost: String): ReadOutcome<Map<String, Int?>> = guarded { inner.storageVersions(vhost) }
    override suspend fun deprecatedFeaturesInUse(): ReadOutcome<Set<String>> = guarded { inner.deprecatedFeaturesInUse() }
}

sealed interface LiveAnalysisOutcome {
    data class Analysed(val held: HeldAnalysis) : LiveAnalysisOutcome
    data class OfferFileImport(val text: String) : LiveAnalysisOutcome
    data class Unreachable(val connectionName: String) : LiveAnalysisOutcome
    data class Failed(val text: String) : LiveAnalysisOutcome
}

/**
 * Fetches [source] and analyses the export on the live facts. The result is not held here: `AnalysisHolder` holds it (decision D1), so it
 * survives a reconnect like a file. CPU work runs off the caller's thread, like the file import.
 */
suspend fun analyseLive(source: DefinitionsSource, dispatcher: CoroutineDispatcher = Dispatchers.Default): LiveAnalysisOutcome = when (val fetched = source.fetch()) {
    is FetchResult.Fetched -> withContext(dispatcher) {
        LiveAnalysisOutcome.Analysed(
            HeldAnalysis(source.connectionName, fetched.document, CompatibilityAnalyzer.analyze(fetched.document, fetched.facts)),
        )
    }
    is FetchResult.NotPermitted -> LiveAnalysisOutcome.OfferFileImport(fetched.reason)
    FetchResult.Unreachable -> LiveAnalysisOutcome.Unreachable(source.connectionName)
    is FetchResult.Failed -> LiveAnalysisOutcome.Failed(fetched.reason)
}

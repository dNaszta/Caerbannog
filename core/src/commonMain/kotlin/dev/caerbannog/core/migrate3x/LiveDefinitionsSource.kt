package dev.caerbannog.core.migrate3x

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

/** M8-02a STUB: not implemented. */
class LiveDefinitionsSource(
    override val connectionName: String,
    private val brokerVersion: String?,
    private val reads: ThreeXReads,
    private val pause: suspend (millis: Long) -> Unit,
) : DefinitionsSource {
    override suspend fun fetch(): FetchResult = FetchResult.Failed("M8-02a STUB")
}

/** M8-02a STUB (wrong on purpose: must refuse with NOT_THREE_X). The default of `StreamSource.definitionsSource`. */
class UnavailableDefinitionsSource(override val connectionName: String) : DefinitionsSource {
    override suspend fun fetch(): FetchResult = FetchResult.Failed("M8-02a STUB")
}

/** Every read fails; never called, because [LiveDefinitionsSource] refuses an unknown or 4.x version before any read. */
object NoThreeXReads : ThreeXReads {
    override suspend fun definitions(): ReadOutcome<String> = ReadOutcome.Failed("no source")
    override suspend fun storageVersions(vhost: String): ReadOutcome<Map<String, Int?>> = ReadOutcome.Failed("no source")
    override suspend fun deprecatedFeaturesInUse(): ReadOutcome<Set<String>> = ReadOutcome.Failed("no source")
}

/** M8-02a STUB: passes through without the guard (must return Unreachable without a read once [isCurrent] is false). */
internal class GuardedDefinitionsSource(private val inner: DefinitionsSource, private val isCurrent: () -> Boolean) : DefinitionsSource {
    override val connectionName: String get() = inner.connectionName
    override suspend fun fetch(): FetchResult = inner.fetch()
}

/** M8-02a STUB: passes through without the guard (must return ReadOutcome.Unreachable for every method once [isCurrent] is false). */
internal class GuardedThreeXReads(private val inner: ThreeXReads, private val isCurrent: () -> Boolean) : ThreeXReads {
    override suspend fun definitions(): ReadOutcome<String> = inner.definitions()
    override suspend fun storageVersions(vhost: String): ReadOutcome<Map<String, Int?>> = inner.storageVersions(vhost)
    override suspend fun deprecatedFeaturesInUse(): ReadOutcome<Set<String>> = inner.deprecatedFeaturesInUse()
}

sealed interface LiveAnalysisOutcome {
    data class Analysed(val held: HeldAnalysis) : LiveAnalysisOutcome
    data class OfferFileImport(val text: String) : LiveAnalysisOutcome
    data class Unreachable(val connectionName: String) : LiveAnalysisOutcome
    data class Failed(val text: String) : LiveAnalysisOutcome
}

/** M8-02a STUB: not implemented. */
suspend fun analyseLive(source: DefinitionsSource): LiveAnalysisOutcome = LiveAnalysisOutcome.Failed("M8-02a STUB")

package dev.caerbannog.core.streams

/** Page request for [StreamCatalog.listStreams]. [nameFilter] is a substring, or a regex with [useRegex]; page is 1-based. */
data class StreamListRequest(
    val page: Int = 1,
    val pageSize: Int = 100,
    val nameFilter: String? = null,
    val useRegex: Boolean = false,
)

/** [rows] are the streams of the page; the counts are the broker's, taken over all queues. */
data class StreamPage(
    val rows: List<StreamSummary>,
    val page: Int,
    val pageCount: Int,
    val pageSize: Int,
    val filteredCount: Int,
    val itemCount: Int,
    val totalCount: Int,
)

/** One stream as the queue object shows it. [messages] is null when the broker sent no statistics yet. */
data class StreamSummary(
    val name: String,
    val vhost: String,
    val messages: Long?,
    val consumers: Int,
    val segments: Int?,
    val firstTimestamp: Long?,
    val leader: String?,
    val node: String?,
    val members: List<String>,
    val online: List<String>,
    val retention: Retention = Retention(),
)

/** Retention arguments of a stream; null means not set. [maxAge] is the broker's text, e.g. `7D`. */
data class Retention(
    val maxAge: String? = null,
    val maxLengthBytes: Long? = null,
    val segmentSizeBytes: Long? = null,
)

data class PublisherRow(
    val connectionName: String,
    val user: String?,
    val publisherId: Int,
    val reference: String?,
    val published: Long,
    val confirmed: Long,
    val errored: Long,
)

data class ConsumerRow(
    val connectionName: String,
    val user: String?,
    val subscriptionId: Int,
    val name: String?,
    val active: Boolean,
    val activityStatus: String?,
    val consumed: Long,
    val credits: Int,
    val offset: Long,
    val offsetLag: Long,
)

/** What the broker and the user allow. */
data class Capabilities(val streamPlugin: PluginStatus, val visibility: Visibility)

enum class PluginStatus { PRESENT, MISSING, UNKNOWN }

/** How much of the connection lists the user sees (spec "Management API facts", S3a/S3b). */
enum class Visibility {
    FULL,
    OWN_CONNECTIONS_ONLY,
    NONE,
    ;

    companion object {
        /** `monitoring` or `administrator` is FULL, `management` is OWN_CONNECTIONS_ONLY, anything else NONE; tags match exactly. */
        fun fromTags(tags: Collection<String>): Visibility = when {
            "monitoring" in tags || "administrator" in tags -> FULL
            "management" in tags -> OWN_CONNECTIONS_ONLY
            else -> NONE
        }
    }
}

/** Typed outcome of a catalog call. */
sealed interface CatalogResult<out T> {
    data class Success<out T>(val value: T) : CatalogResult<T>

    /** Every failure; the variants are objects so they compare equal and carry no broker text. */
    sealed interface Failure : CatalogResult<Nothing>

    /** HTTP 401 or 403: no management tag, or the credentials were refused. */
    data object NotAuthorised : Failure

    /** `rabbitmq_stream_management` is not enabled. */
    data object PluginMissing : Failure

    /** Page 0, past the last page, or a page size outside 1..500. */
    data object BadPaging : Failure

    /** The stream (or the vhost for this user) does not exist. */
    data object NotFound : Failure

    /** Timeout, refused connection, TLS failure or a closed connection. */
    data object Unreachable : Failure

    /** A status or body this app does not understand. */
    data object Unexpected : Failure
}

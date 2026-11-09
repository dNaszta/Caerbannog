package dev.caerbannog.datarabbit

/** A request the migration executor would have sent outside its allow-list; caught once, in the executor, and shown as a fixed failure. */
internal class PathNotAllowedException : RuntimeException("path not allowed")

/**
 * The only requests the migration executor may send (CLAUDE.md safety rule), checked on the encoded path without the base URL.
 * It is a test of SHAPE, not of names: after `/api` there are exactly
 *  - 3 segments `{queues|exchanges|policies|operator-policies}/{vhost}/{name}` for GET, PUT and DELETE,
 *  - 6 segments `bindings/{vhost}/e/{source}/{q|e}/{destination}` for GET and POST, and 7 with the properties key for DELETE.
 * A name can only stand in a name position, so no suffix such as `/get`, `/contents` or `/purge` can follow it; a queue that is itself
 * named `get` is a name like any other. Every segment is non-empty, is not a raw `.` or `..`, and holds only unreserved characters or
 * `%XX`, so no slash, query, fragment or dot segment from input reaches the URL.
 */
internal object ExecutorPathAllowList {

    private val collections = setOf("queues", "exchanges", "policies", "operator-policies")
    private val ITEM_METHODS = setOf("GET", "PUT", "DELETE")
    private val segmentPattern = Regex("""(?:[A-Za-z0-9._~*-]|%[0-9A-Fa-f]{2})+""")

    fun isAllowed(method: String, path: String): Boolean {
        if (!path.startsWith("/api/")) return false
        val segments = path.removePrefix("/api/").split('/')
        if (segments.any { it == "." || it == ".." || !segmentPattern.matches(it) }) return false
        return when (segments.first()) {
            in collections -> segments.size == ITEM_SEGMENTS && method in ITEM_METHODS
            "bindings" -> isBindingShape(segments) && when (segments.size) {
                BINDING_SEGMENTS -> method == "GET" || method == "POST"
                else -> method == "DELETE"
            }
            else -> false
        }
    }

    /** [path] itself when it is allowed for [method]; otherwise nothing is sent: it throws [PathNotAllowedException]. */
    fun checked(method: String, path: String): String =
        if (isAllowed(method, path)) path else throw PathNotAllowedException()

    private fun isBindingShape(segments: List<String>): Boolean =
        (segments.size == BINDING_SEGMENTS || segments.size == BINDING_SEGMENTS + 1) &&
            segments[2] == "e" && (segments[4] == "q" || segments[4] == "e")

    private const val ITEM_SEGMENTS = 3
    private const val BINDING_SEGMENTS = 6
}

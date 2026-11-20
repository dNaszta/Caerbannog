package dev.caerbannog.datarabbit

/**
 * The only requests the shovel gateway may send (CLAUDE.md safety rule), checked on the encoded path without the base URL: `GET /api/whoami`,
 * `GET /api/overview`, `GET /api/shovels`, `GET /api/queues/{v}/{q}`, `PUT` and `DELETE /api/parameters/shovel/{v}/{n}`. Nothing else, in particular never a GET of
 * `/api/parameters/...` (it returns the source password in clear), `/api/definitions`, `/get`, `/contents`, `/purge` or a queue DELETE.
 * A test of SHAPE: every segment is non-empty, not `.` or `..`, and holds only unreserved characters or `%XX`, so no suffix, query or fragment passes.
 */
internal object ShovelPathAllowList {
    private val segment = Regex("""(?:[A-Za-z0-9._~-]|%[0-9A-Fa-f]{2})+""")

    fun isAllowed(method: String, path: String): Boolean {
        if (!path.startsWith("/api/")) return false
        val parts = path.removePrefix("/api/").split('/')
        if (parts.any { it == "." || it == ".." || !segment.matches(it) }) return false
        return when {
            method == "GET" && parts == listOf("whoami") -> true
            method == "GET" && parts == listOf("shovels") -> true
            // M8-15: the destination version, read once per dry run; nothing else under /api/ besides the shapes below.
            method == "GET" && parts == listOf("overview") -> true
            method == "GET" -> parts.size == 3 && parts[0] == "queues"
            method == "PUT" || method == "DELETE" -> parts.size == 4 && parts[0] == "parameters" && parts[1] == "shovel"
            else -> false
        }
    }

    /** [path] itself when allowed; otherwise it throws [PathNotAllowedException] and nothing is sent. */
    fun checked(method: String, path: String): String = if (isAllowed(method, path)) path else throw PathNotAllowedException()
}

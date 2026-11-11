package dev.caerbannog.datarabbit

/**
 * The only requests the shovel gateway may send (CLAUDE.md safety rule), checked on the encoded path without the base URL: `GET /api/whoami`,
 * `GET /api/shovels`, `GET /api/queues/{v}/{q}`, `PUT` and `DELETE /api/parameters/shovel/{v}/{n}`. Nothing else, in particular never a GET of
 * `/api/parameters/...` (it returns the source password in clear), `/api/definitions`, `/get`, `/contents`, `/purge` or a queue DELETE.
 */
internal object ShovelPathAllowList {
    fun isAllowed(method: String, path: String): Boolean = false // M8-08 STUB

    /** [path] itself when allowed; otherwise it throws [PathNotAllowedException] and nothing is sent. */
    fun checked(method: String, path: String): String = if (isAllowed(method, path)) path else throw PathNotAllowedException() // M8-08 STUB
}

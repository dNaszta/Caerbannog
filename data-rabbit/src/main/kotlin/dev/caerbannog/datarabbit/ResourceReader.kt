package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.MigrationAction

/** The one GET of the migration executor: the resource an action is about, always through the path allow-list. */
internal class ResourceReader(private val http: ManagementHttp, private val requests: ManagementRequests) {

    /** @throws PathNotAllowedException when the path of the action is not of an allowed shape; nothing is sent then. */
    suspend fun read(action: MigrationAction): ManagementReply {
        val path = ExecutorPathAllowList.checked("GET", MigrationReadRequests.pathFor(action))
        return http.get(requests.absolute(path))
    }
}

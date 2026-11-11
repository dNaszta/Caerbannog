package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.MigrationAction

/** The one GET of the migration executor: the resource an action is about, always through the path allow-list. */
internal class ResourceReader(private val http: ManagementHttp, private val requests: ManagementRequests) {

    /** @throws PathNotAllowedException when the path of the action is not of an allowed shape; nothing is sent then. */
    /** `GET /api/definitions` of the target: only [RedactedDefinitions] may take the body (it carries Shovel and federation passwords in clear). */
    suspend fun definitions(): ManagementReply = http.get(requests.absolute(ExecutorPathAllowList.checked("GET", "/api/definitions")))

    suspend fun read(action: MigrationAction): ManagementReply {
        val path = ExecutorPathAllowList.checked("GET", MigrationReadRequests.pathFor(action))
        return http.get(requests.absolute(path))
    }
}

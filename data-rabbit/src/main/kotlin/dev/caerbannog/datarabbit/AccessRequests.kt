package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.MigrationAction

/**
 * The PUT of each access action (M8-12c): `PUT /api/vhosts/{name}`, `/api/users/{name}` (password_hash, hashing_algorithm, tags), `/api/permissions/{v}/{u}`,
 * `/api/topic-permissions/{v}/{u}`. The ONE place of data-rabbit that calls `SecretValue.expose()` (the user request body). M8-12c STUB: sends nothing.
 */
internal object AccessRequests {
    fun requestFor(action: MigrationAction): ManagementWrite? = null // M8-12c STUB
}

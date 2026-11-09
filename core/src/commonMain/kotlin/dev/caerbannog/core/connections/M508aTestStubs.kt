package dev.caerbannog.core.connections

import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.TopologyMigrationExecutor

/**
 * M5-08a COMPILE STUB (test developer, step 2). DELETE THIS FILE at implementation and write the real guard (pattern of
 * [GuardedStreamManager]): once the connection is no longer the current one, `execute` returns `ActionResult.Failed("Connection closed")`
 * and never calls [inner]. The stub forwards always (wrong), so `GuardedMigrationExecutorTest` is red. `ActiveConnection.guardedSource`
 * does not pass the executor yet (STUB: the source has none), so `ActiveConnectionMigrationExecutorTest` is red too.
 */
internal class GuardedMigrationExecutor(
    private val inner: TopologyMigrationExecutor,
    private val isCurrent: () -> Boolean,
) : TopologyMigrationExecutor {
    override suspend fun execute(action: MigrationAction): ActionResult = inner.execute(action)
}

package dev.caerbannog.core.migrations

/**
 * M5-08a COMPILE STUBS (test developer, step 2). DELETE THIS FILE at implementation: the real code replaces every line.
 * Stubs live here and in marked `M5-08a STUB` edits of `MigrationRunner.kt` (the `vhost` parameters), `MigrationModels.kt`
 * (`ActionFailed.index` and `total`), `ConnectionGateway.kt` and `StreamSource.kt` (`migrationExecutor`, final shape already).
 * `MigrationArchitectureGuardTest` checks that this file is gone.
 */

/**
 * Thrown by `MigrationsViewModel.exportCurrentTopology` when the broker could not be read: the error "The topology could not be read" is in
 * the state and NO file was written. (A throw rather than a nullable result so that the existing callers and tests keep compiling.)
 */
class TopologyReadException : RuntimeException("The topology could not be read")

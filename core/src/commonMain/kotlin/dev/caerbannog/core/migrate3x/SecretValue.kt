package dev.caerbannog.core.migrate3x

/**
 * M8-12a: the class moved to `core.json` (the migration format holds a password hash and `migrations` must not import `migrate3x`, see
 * MigrationArchitectureGuardTest). Every existing import of this name keeps working through the alias.
 */
typealias SecretValue = dev.caerbannog.core.json.SecretValue

package dev.caerbannog.core.migrations

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M9-12 step 2 (RED by compilation until the function exists): `suspend fun appliedOn(version: String, profileNames: List<String>, history: MigrationHistoryRepository): List<String>`
 * in `dev.caerbannog.core.migrations`: the saved profiles that have [version] in their applied history, in the order of [profileNames]. It reads only
 * (`getAppliedMigrations`). The history of a profile that has none, or that is not listed, says nothing. The desktop app passes every saved profile.
 */
class AppliedOnTest {
    private val history = InMemoryMigrationHistoryRepository()

    private fun on(version: String, vararg profiles: String) = runBlocking { appliedOn(version, profiles.toList(), history) }

    @Test
    fun `a version applied on one profile names it`() {
        runBlocking { history.recordApplied("prod-eu", "0002", "c2") }
        assertEquals(listOf("prod-eu"), on("0002", "uat-eu", "prod-eu"))
    }

    @Test
    fun `a version applied on none gives an empty list`() {
        runBlocking { history.recordApplied("prod-eu", "0002", "c2") }
        assertEquals(emptyList(), on("0005", "uat-eu", "prod-eu"))
    }

    @Test
    fun `several profiles come back in the order given`() {
        runBlocking {
            history.recordApplied("uat-eu", "0002", "c")
            history.recordApplied("prod-eu", "0002", "c")
            history.recordApplied("dev", "0003", "c")
        }
        assertEquals(listOf("prod-eu", "uat-eu"), on("0002", "prod-eu", "dev", "uat-eu"))
    }

    @Test
    fun `a modified file counts as applied, the version is the key and not the checksum`() {
        runBlocking { history.recordApplied("prod-eu", "0002", "an-older-checksum") }
        assertEquals(listOf("prod-eu"), on("0002", "prod-eu"))
    }

    @Test
    fun `a profile that is not listed is not asked`() {
        runBlocking { history.recordApplied("gone", "0002", "c") }
        assertEquals(emptyList(), on("0002", "uat-eu"))
    }

    @Test
    fun `no profiles gives an empty list`() = assertEquals(emptyList(), on("0002"))
}

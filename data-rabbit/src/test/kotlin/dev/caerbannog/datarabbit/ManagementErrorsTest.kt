package dev.caerbannog.datarabbit

import dev.caerbannog.core.streams.CatalogResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M2-03 part 2 (no Docker): every recorded error body maps to its typed error, and the two 401 bodies of the
 * connection probe are told apart (M2-03a: `Not management user` is NOT a failure, any other 401 is). Contract,
 * `internal object ManagementErrors` in package `dev.caerbannog.datarabbit`:
 *   map(status: Int, on404: CatalogResult.Failure): CatalogResult.Failure     (M2-04 F1: the unused `body` parameter is gone, and so is its @Suppress)
 *     400 -> BadPaging, 401 and 403 -> NotAuthorised, 404 -> [on404] (PluginMissing for the plugin and publishers
 *     paths, NotFound for a single stream), anything else -> Unexpected; the body never changed the class, so the function does not take it
 *   isNotManagementUser(body: String): Boolean   (true only for reason exactly `Not management user`; anything else, empty or malformed included, false)
 */
class ManagementErrorsTest {

    private fun mapRecorded(path: String, on404: CatalogResult.Failure = CatalogResult.NotFound): CatalogResult.Failure {
        val (status, _) = FixtureFiles.errorBody(path)
        return ManagementErrors.map(status, on404)
    }

    @Test
    fun `plugin missing 404 maps to PluginMissing on the plugin path`() =
        assertEquals(CatalogResult.PluginMissing, mapRecorded("m2-02/plugin-missing-404.json", on404 = CatalogResult.PluginMissing))

    @Test
    fun `the same 404 on a single stream is NotFound, not PluginMissing`() =
        assertEquals(CatalogResult.NotFound, mapRecorded("m2-02/plugin-missing-404.json", on404 = CatalogResult.NotFound))

    @Test
    fun `no management tag 401 maps to NotAuthorised`() =
        assertEquals(CatalogResult.NotAuthorised, mapRecorded("m2-02/non-admin-no-tag-401.json"))

    @Test
    fun `wrong password 401 also maps to NotAuthorised on a data call`() =
        assertEquals(CatalogResult.NotAuthorised, mapRecorded("m2-03a/wrong-password-401.json"))

    @Test
    fun `page size 501 maps to BadPaging`() = assertEquals(CatalogResult.BadPaging, mapRecorded("m2-02/queues-page-size-501-error.json"))

    @Test
    fun `a 400 with an empty body is BadPaging`() = assertEquals(CatalogResult.BadPaging, ManagementErrors.map(400, CatalogResult.NotFound))

    @Test
    fun `403 is NotAuthorised and server errors are Unexpected`() {
        assertEquals(CatalogResult.NotAuthorised, ManagementErrors.map(403, CatalogResult.NotFound))
        for (status in listOf(500, 502, 503, 504, 418, 302)) {
            assertEquals(CatalogResult.Unexpected, ManagementErrors.map(status, CatalogResult.NotFound), "status $status")
        }
    }

    @Test
    fun `an unparseable error body does not change the mapping`() {
        assertEquals(CatalogResult.NotAuthorised, ManagementErrors.map(401, CatalogResult.NotFound))
        assertEquals(CatalogResult.BadPaging, ManagementErrors.map(400, CatalogResult.NotFound))
    }

    // --- the connection probe: which 401 is a failed Connect ------------------------------------

    @Test
    fun `the no-tag 401 is recognised as not a management user`() {
        val (_, body) = FixtureFiles.errorBody("m2-03a/not-management-user-401.json")
        assertTrue(ManagementErrors.isNotManagementUser(body), body)
    }

    @Test
    fun `the wrong-password 401 is not the no-tag case`() {
        val (_, body) = FixtureFiles.errorBody("m2-03a/wrong-password-401.json")
        assertFalse(ManagementErrors.isNotManagementUser(body), body)
    }

    @Test
    fun `anything else defaults to credentials refused`() {
        for (body in listOf("", "not json", "{}", """{"reason":"not management user"}""", """{"reason":"Not management user "}""", """{"reason":null}""", """{"reason":5}""", "[]")) {
            assertFalse(ManagementErrors.isNotManagementUser(body), "body <$body>")
        }
    }

    // --- M2-04 F1: the unused body parameter is gone ----------------------------------------------------

    @Test
    fun `map takes the status and the meaning of a 404 only`() {
        val signature = ManagementErrors::class.java.declaredMethods.single { it.name == "map" }
        assertEquals(listOf(Int::class.javaPrimitiveType, CatalogResult.Failure::class.java), signature.parameterTypes.toList())
    }

    @Test
    fun `the unused parameter suppression is gone from the source`() {
        val source = java.io.File("src/main/kotlin/dev/caerbannog/datarabbit/ManagementErrors.kt").readText()
        assertFalse(source.contains("UNUSED_PARAMETER"), "the suppression only existed for the dropped body parameter")
    }
}

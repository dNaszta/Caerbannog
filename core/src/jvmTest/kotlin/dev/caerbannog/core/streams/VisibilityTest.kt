package dev.caerbannog.core.streams

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M2-03 part 2: the visibility rule of the spec ("Management API facts", S3, decided S3a/S3b): from the tags of
 * `GET /api/whoami`, `monitoring` or `administrator` = FULL, `management` = OWN_CONNECTIONS_ONLY, no tag = NONE.
 * Contract: `enum class Visibility { FULL, OWN_CONNECTIONS_ONLY, NONE }` with `Visibility.fromTags(tags: Collection<String>)`.
 */
class VisibilityTest {

    @Test
    fun `monitoring gives the full lists`() = assertEquals(Visibility.FULL, Visibility.fromTags(listOf("monitoring")))

    @Test
    fun `administrator gives the full lists`() = assertEquals(Visibility.FULL, Visibility.fromTags(listOf("administrator")))

    @Test
    fun `management alone gives only the own connections`() =
        assertEquals(Visibility.OWN_CONNECTIONS_ONLY, Visibility.fromTags(listOf("management")))

    @Test
    fun `the strongest tag wins whatever the order`() {
        assertEquals(Visibility.FULL, Visibility.fromTags(listOf("management", "monitoring")))
        assertEquals(Visibility.FULL, Visibility.fromTags(listOf("monitoring", "management")))
        assertEquals(Visibility.FULL, Visibility.fromTags(listOf("management", "administrator")))
    }

    @Test
    fun `no tag gives none`() = assertEquals(Visibility.NONE, Visibility.fromTags(emptyList()))

    @Test
    fun `a tag that opens nothing gives none`() = assertEquals(Visibility.NONE, Visibility.fromTags(listOf("impersonator")))

    @Test
    fun `tags are matched exactly, not by prefix or case`() {
        assertEquals(Visibility.NONE, Visibility.fromTags(listOf("monitoring-extra")))
        assertEquals(Visibility.NONE, Visibility.fromTags(listOf("Monitoring")))
    }
}

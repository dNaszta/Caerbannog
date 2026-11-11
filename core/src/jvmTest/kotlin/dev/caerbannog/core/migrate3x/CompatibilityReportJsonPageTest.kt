package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M8-10: `CompatibilityReportJson.encodePage` is pure: the header and finding JSON of `encode`, one page of findings, and a `page` object with offset, limit, returned and total. */
class CompatibilityReportJsonPageTest {
    private val report = ScreenFiles.held(DefinitionsFiles.classicQueues(40)).report
    private val total = report.findings.size
    private fun page(limit: Int, offset: Int): JsonObject = Json.parseToJsonElement(CompatibilityReportJson.encodePage(report, limit, offset)).jsonObject
    private val full: JsonObject = Json.parseToJsonElement(CompatibilityReportJson.encode(report)).jsonObject

    @Test
    fun `the page carries the header of the full report, the findings of the slice and the paging numbers`() {
        val p = page(5, 10)
        assertEquals(full["schema"], p["schema"]); assertEquals(full["source"], p["source"]); assertEquals(full["summary"], p["summary"])
        assertEquals(full["findings"]!!.jsonArray.subList(10, 15).toList(), p["findings"]!!.jsonArray.toList())
        val meta = p["page"]!!.jsonObject
        assertEquals(listOf(10, 5, 5, total), listOf("offset", "limit", "returned", "total").map { meta[it]!!.jsonPrimitive.int })
    }

    @Test
    fun `the key set is the full report's plus page, no new field`() {
        assertEquals(full.keys + "page", page(3, 0).keys)
    }

    @Test
    fun `pages of any size put together are the whole list of findings in report order`() {
        for (size in listOf(1, 7, 50, total)) {
            val ids = (0 until total step size).flatMap { off -> page(size, off)["findings"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content } }
            assertEquals(report.findings.map { it.id }, ids, "page size $size")
        }
    }

    @Test
    fun `the last page is short, an offset at or past the end has no findings and still the total`() {
        val last = page(50, total - 3)
        assertEquals(3, last["findings"]!!.jsonArray.size)
        assertEquals(3, last["page"]!!.jsonObject["returned"]!!.jsonPrimitive.int)
        for (offset in listOf(total, total + 1000)) {
            val p = page(50, offset)
            assertEquals(0, p["findings"]!!.jsonArray.size)
            assertEquals(total, p["page"]!!.jsonObject["total"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun `a negative offset counts as 0 and a limit below 1 counts as 1`() {
        assertEquals(page(4, 0)["findings"], page(4, -5)["findings"])
        assertEquals(page(1, 0)["findings"], page(0, 0)["findings"])
        assertEquals(page(1, 0)["findings"], page(-3, 0)["findings"])
        assertEquals(1, page(0, 0)["page"]!!.jsonObject["limit"]!!.jsonPrimitive.int)
    }

    @Test
    fun `the page is deterministic and an empty report has an empty page`() {
        assertEquals(CompatibilityReportJson.encodePage(report, 9, 3), CompatibilityReportJson.encodePage(report, 9, 3))
        val empty = report.copy(findings = emptyList())
        val p = Json.parseToJsonElement(CompatibilityReportJson.encodePage(empty, 10, 0)).jsonObject
        assertEquals(JsonArray(emptyList()), p["findings"])
        val meta = p["page"]?.jsonObject ?: throw AssertionError("no page object in: ${p.keys}")
        assertEquals(0, meta["total"]!!.jsonPrimitive.int)
        assertTrue(meta["returned"]!!.jsonPrimitive.int == 0)
    }
}

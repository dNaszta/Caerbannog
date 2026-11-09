package dev.caerbannog.core.migrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.fail

/** M8-04 helpers shared by the migration-format tests. */
internal object MigrationTestSupport {

    /** A migration file with one action; [actionJson] is the text of the action object. */
    fun fileWith(vararg actionJson: String, version: String = "0001"): String =
        """{ "version": "$version", "description": "m8-04", "up": [ ${actionJson.joinToString(", ")} ] }"""

    fun actions(raw: String): List<MigrationAction> = when (val r = MigrationParser.parse(raw)) {
        is MigrationValidationResult.Valid -> r.migration.up
        is MigrationValidationResult.Invalid -> fail("expected a valid file but got Invalid(\"${r.reason}\") for: ${raw.take(300)}")
    }

    fun only(raw: String): MigrationAction = actions(raw).single()

    fun reason(raw: String): String = when (val r = MigrationParser.parse(raw)) {
        is MigrationValidationResult.Invalid -> r.reason
        is MigrationValidationResult.Valid -> fail("expected the file to be refused but it parsed to ${r.migration.up}: ${raw.take(300)}")
    }

    /** The key order of every action object of an exported file, in written order. */
    fun keyOrders(exported: String): List<List<String>> =
        Json.parseToJsonElement(exported).jsonObject["up"]!!.jsonArray.map { (it as JsonObject).keys.toList() }

    fun nested(levels: Int, leaf: String = "1"): String = "[".repeat(levels) + leaf + "]".repeat(levels)
}

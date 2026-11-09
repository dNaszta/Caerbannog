package dev.caerbannog.core.json

// =====================================================================================================================
// M8-04 TEST-FIRST COMPILE STUBS. NOT PRODUCTION CODE. The senior developer deletes this whole file.
//
// The real ArgumentValue lives in dev.caerbannog.core.migrate3x (ArgumentValue.kt, M8-01). The M8-04 tests already import it
// from its FINAL package dev.caerbannog.core.json. A typealias cannot expose the nested classes (`ArgumentValue.Whole`), so
// this file holds a throw-away COPY of the type. The M8-01 / M8-03 code and tests still use the migrate3x type and are untouched.
// The developer's move: `git mv migrate3x/ArgumentValue.kt json/ArgumentValue.kt`, change its package line, delete THIS file,
// create ArgumentValueJson in json/, and fix the imports of the M8-01 / M8-03 files listed in the brief. The model in
// migrations/MigrationModels.kt already imports dev.caerbannog.core.json.ArgumentValue.
// =====================================================================================================================

const val MAX_ARGUMENT_DEPTH: Int = 32

sealed interface ArgumentValue {
    data class Text(val value: String) : ArgumentValue
    data class Whole(val value: Long) : ArgumentValue
    data class Decimal(val value: Double) : ArgumentValue
    data class Flag(val value: Boolean) : ArgumentValue
    data class Items(val items: List<ArgumentValue>) : ArgumentValue
    data class Table(val entries: Map<String, ArgumentValue>) : ArgumentValue
    data object Nothing : ArgumentValue
}

object ArgumentValueJson {
    fun parse(element: kotlinx.serialization.json.JsonElement, maxDepth: Int = MAX_ARGUMENT_DEPTH): ArgumentValue? =
        TODO("M8-04 stub: ArgumentValueJson.parse")

    fun encode(value: ArgumentValue): kotlinx.serialization.json.JsonElement =
        TODO("M8-04 stub: ArgumentValueJson.encode")
}

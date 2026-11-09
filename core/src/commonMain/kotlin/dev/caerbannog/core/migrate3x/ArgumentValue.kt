package dev.caerbannog.core.migrate3x

/** Nesting limit of [ArgumentValue.Items] and [ArgumentValue.Table] (the top-level map is level 0). Guards untrusted input. */
const val MAX_ARGUMENT_DEPTH: Int = 32

/** A typed JSON value from a queue, exchange or binding argument, a policy definition or a user limit. */
sealed interface ArgumentValue {
    data class Text(val value: String) : ArgumentValue
    data class Whole(val value: Long) : ArgumentValue
    data class Decimal(val value: Double) : ArgumentValue
    data class Flag(val value: Boolean) : ArgumentValue
    data class Items(val items: List<ArgumentValue>) : ArgumentValue
    data class Table(val entries: Map<String, ArgumentValue>) : ArgumentValue

    /** JSON null. */
    data object Nothing : ArgumentValue
}

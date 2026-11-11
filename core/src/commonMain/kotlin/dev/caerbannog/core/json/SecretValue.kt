package dev.caerbannog.core.json

/**
 * A secret text (a user password hash from a 3.x export) that cannot be read by accident.
 *
 * Deliberately a plain class: no generated `toString`, `equals`, `hashCode` or `copy`, so no content-derived
 * value leaks through a string template, a hash set or a map key. Equality is identity; use [matches] to compare content.
 */
class SecretValue private constructor(private val text: String) {

    /** Always the constant text, never a length, prefix or hash of the content. */
    override fun toString(): String = REDACTED

    /** True when both values hold the same text. */
    fun matches(other: SecretValue): Boolean = text == other.text

    /** The text. Only for the migration file writer (M8-05) and the comparison (M8-12); never log, show or serialize. */
    fun expose(): String = text

    companion object {
        private const val REDACTED = "SecretValue(***)"

        fun of(text: String): SecretValue = SecretValue(text)
    }
}

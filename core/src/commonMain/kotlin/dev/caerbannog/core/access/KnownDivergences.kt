package dev.caerbannog.core.access

/** When a [Divergence] applies: the broker's Erlang `re` reads BYTES without UTF mode and PCRE's `$` also matches before a final line feed; RE2/J reads characters and `$` only at the very end. */
enum class DivergenceWhen {
    /** The name (or routing key) the pattern is applied to has a character above U+007F. */
    NameHasNonAscii,

    /** The name (or routing key) ends in a line feed. */
    NameEndsWithLineFeed,
}

/** [construct]: a pattern that CONTAINS this text is affected (by shape, not parsed; `\$` also contains `$`, which is the safe direction). [shape]: optional regex for a family of spellings of the same construct. [row]: the live row that proved it. */
data class Divergence(val construct: String, val whenName: DivergenceWhen, val row: String, val shape: Regex? = null) {
    /** True when [pattern] contains the construct: by [shape] when given (a family such as every inline flag group with `i`), else by the literal [construct] text. */
    fun appearsIn(pattern: String): Boolean = shape?.containsMatchIn(pattern) ?: pattern.contains(construct)
}

/**
 * M10-04 contract: constructs that the engine ACCEPTS but that the live differential rows (`RealBrokerAccessMatchTest`, `AccessMatchCases`) show to be read
 * differently by the broker's Erlang `re` (PCRE 10.47 on the pinned image 4.4.1, Erlang 29.1.2). A pattern that contains [Divergence.construct], applied to a name that meets
 * [Divergence.whenName], is [AccessAnswer.UnreadablePattern]. Data only; every entry names the live row that proved it. A construct not listed was either agreeing in
 * every row measured, or was not measured (`\D`, `\s` with a non-ASCII name: see the report of M10-04 step 2).
 */
data class KnownDivergences(val entries: List<Divergence>) {
    companion object {
        /** Any inline flag group that sets `i`: `(?i)`, `(?si)`, `(?im)`, `(?i:...)`. `(?-i)` also matches, which is the conservative direction. */
        private val INLINE_FLAG_WITH_I = Regex("""\(\?[a-zA-Z-]*i[a-zA-Z-]*[:)]""")

        /** Measured by `RealBrokerAccessMatchTest` on 2026-11-27 (rows of `AccessMatchNewRows`, ids `NEW-nnn`; the rows keep both answers). */
        val Measured: KnownDivergences = KnownDivergences(
            listOf(
                Divergence("$", DivergenceWhen.NameEndsWithLineFeed, "NEW-047 ^ex$ / ex<LF>"),
                Divergence("(?i)", DivergenceWhen.NameHasNonAscii, "NEW-065 (?i)Á / あ (measured: RE2/J folds byte C3 to E3, the broker does not); NEW-064 is conservative, not a measured difference", INLINE_FLAG_WITH_I),
            ),
        )
    }
}

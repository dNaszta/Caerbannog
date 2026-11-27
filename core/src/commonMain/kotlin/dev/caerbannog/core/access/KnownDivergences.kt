package dev.caerbannog.core.access

/**
 * M10-04 contract: constructs that the engine ACCEPTS but that the live differential rows (`RealBrokerAccessMatchTest`, `AccessMatchCases`) show to be read
 * differently by the broker's Erlang `re`. A pattern that contains one is [AccessAnswer.UnreadablePattern]. Data only; every entry names the live row that proved it.
 */
data class Divergence(val construct: String, val row: String, val onlyForNonAsciiName: Boolean = false)

data class KnownDivergences(val entries: List<Divergence>) {
    companion object {
        /** The measured list. Filled from the live rows of M10-04 part A; empty when the engine and the broker agreed everywhere. */
        val Measured: KnownDivergences = KnownDivergences(emptyList())
    }
}

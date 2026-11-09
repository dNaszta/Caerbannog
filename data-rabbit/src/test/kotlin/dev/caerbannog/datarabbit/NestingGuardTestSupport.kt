package dev.caerbannog.datarabbit

/** M5-07: the deep bodies the nesting tests feed to the untrusted-JSON call sites (10,000 levels overflowed the kotlinx tree reader in M8-01). */
internal object DeepJson {
    const val LEVELS = 10_000
    fun arrays(depth: Int = LEVELS) = "[".repeat(depth) + "]".repeat(depth)
    fun objects(depth: Int = LEVELS) = "{\"a\":".repeat(depth) + "1" + "}".repeat(depth)
}

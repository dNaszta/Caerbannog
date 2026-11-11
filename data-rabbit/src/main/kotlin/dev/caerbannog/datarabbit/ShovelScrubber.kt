package dev.caerbannog.datarabbit

/** Removes secrets and `scheme://user[:pw]@` from broker text before it becomes a reason. */
internal object ShovelScrubber {
    private val credentialsInUri = Regex("""[A-Za-z][A-Za-z0-9+.-]*://[^@\s]*@""")

    fun scrub(text: String, secrets: List<String> = emptyList()): String =
        secrets.filter { it.isNotEmpty() }.fold(text) { acc, secret -> acc.replace(secret, "***") }.replace(credentialsInUri, "***@")
}

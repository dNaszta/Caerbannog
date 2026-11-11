package dev.caerbannog.core.forwarding

/** Removes secrets and `scheme://user[:pw]@` from broker text before it becomes a reason; shared by the shovel gateway and the read-only MCP tool. */
object ShovelScrubber {
    private val credentialsInUri = Regex("""[A-Za-z][A-Za-z0-9+.-]*://[^@\s]*@""")

    fun scrub(text: String, secrets: List<String> = emptyList()): String =
        secrets.filter { it.isNotEmpty() }.fold(text) { acc, secret -> acc.replace(secret, "***") }.replace(credentialsInUri, "***@")
}

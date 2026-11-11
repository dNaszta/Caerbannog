package dev.caerbannog.datarabbit

/** Removes secrets and `scheme://user[:pw]@` from broker text before it becomes a reason. M8-08 STUB: returns the text as it is. */
internal object ShovelScrubber {
    fun scrub(text: String, secrets: List<String> = emptyList()): String = text // M8-08 STUB
}

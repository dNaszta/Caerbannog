package dev.caerbannog.core.mqtt

/** Where a generated `correlation-id` comes from: a UUID in the app, a counter in tests. Keeps `core` free of platform id generators. */
fun interface CorrelationIdSource {
    fun next(): String
}

/** A warning the Publish MQTT dialog shows while a header named [key] (trimmed, case-sensitive) is in the list. It never blocks Publish. */
data class HeaderNotice(val key: String, val text: String)

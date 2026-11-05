package dev.caerbannog.devseed

/** Deterministic message bodies, so a message seen in the Management UI says which stream and index it came from. */
object MessageGenerator {
    fun body(stream: String, index: Int): ByteArray =
        "dev-seed message $index for stream $stream".toByteArray(Charsets.UTF_8)

    /** A lazy sequence of exactly [count] bodies for the indexes 0 until [count]. */
    fun messages(stream: String, count: Int): Sequence<ByteArray> {
        require(count >= 0) { "count must not be negative" }
        return (0 until count).asSequence().map { body(stream, it) }
    }
}

package dev.caerbannog.devseed

/** Seam over the stream client for `--hold` (M2-06-F-A); StreamPublisher is the real one, tests use a fake. */
internal interface HoldConnector {
    /** Connects ONE named publisher (producer name = [reference]) to [stream]; closing the result disconnects it. */
    fun openPublisher(stream: String, reference: String): AutoCloseable

    /** Connects ONE named consumer ([name], stores its offset) to [stream]; closing the result disconnects it. */
    fun openConsumer(stream: String, name: String): AutoCloseable
}

/** Keeps one named publisher and one named consumer per stream connected until told to stop. */
internal class Hold(
    private val connector: HoldConnector,
    private val streams: List<String>,
    private val log: (String) -> Unit,
) : AutoCloseable {
    private val open = mutableListOf<Pair<String, AutoCloseable>>()

    /** Opens everything, calls [awaitStop] (blocks until Ctrl+C; may throw on cancellation), then always closes. */
    fun run(awaitStop: () -> Unit) {
        try {
            for (stream in streams) {
                open += "publisher $PUBLISHER_REFERENCE on $stream" to connector.openPublisher(stream, PUBLISHER_REFERENCE)
                open += "consumer $CONSUMER_NAME on $stream" to connector.openConsumer(stream, CONSUMER_NAME)
            }
            log("Holding ${open.size} connections on ${streams.joinToString()}. Press Ctrl+C to stop.")
            awaitStop()
        } finally {
            close()
        }
    }

    /** Closes every open handle once; a failing close is logged and does not stop the others. */
    @Synchronized
    override fun close() {
        val handles = open.toList()
        open.clear()
        for ((what, handle) in handles.asReversed()) {
            try {
                handle.close()
                log("Closed $what.")
            } catch (e: Exception) {
                log("Could not close $what: ${e::class.simpleName}: ${e.message}")
            }
        }
    }

    companion object {
        const val PUBLISHER_REFERENCE = "dev-seed-publisher"
        const val CONSUMER_NAME = "dev-seed-consumer"
    }
}

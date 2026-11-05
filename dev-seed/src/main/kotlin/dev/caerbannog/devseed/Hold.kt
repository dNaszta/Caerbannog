package dev.caerbannog.devseed

/** Seam over the stream client for `--hold` (M2-06-F-A); StreamPublisher is the real one, tests use a fake. */
internal interface HoldConnector {
    /** Connects ONE named publisher (producer name = [reference]) to [stream]; closing the result disconnects it. */
    fun openPublisher(stream: String, reference: String): AutoCloseable

    /** Connects ONE named consumer ([name], stores its offset) to [stream]; closing the result disconnects it. */
    fun openConsumer(stream: String, name: String): AutoCloseable
}

/** Stub, no behaviour yet. */
internal class Hold(
    private val connector: HoldConnector,
    private val streams: List<String>,
    private val log: (String) -> Unit,
) : AutoCloseable {
    /** Opens everything, calls [awaitStop] (blocks until Ctrl+C; may throw on cancellation), then always closes. */
    fun run(awaitStop: () -> Unit) {}

    override fun close() {}

    companion object {
        const val PUBLISHER_REFERENCE = "dev-seed-publisher"
        const val CONSUMER_NAME = "dev-seed-consumer"
    }
}

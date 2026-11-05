package dev.caerbannog.devseed

/** Names for the generated queues of the many-queues vhost. */
object QueueNames {
    /**
     * [count] distinct names, zero-padded to eight digits so lexical order is numeric order and a smaller count is
     * a prefix of a larger one.
     */
    fun generate(count: Int): List<String> {
        require(count >= 0) { "count must not be negative" }
        return List(count) { "many-queue-%08d".format(it) }
    }
}

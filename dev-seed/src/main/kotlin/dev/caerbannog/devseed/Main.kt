package dev.caerbannog.devseed

import kotlin.system.exitProcess

private const val USAGE =
    "Usage: dev:seed --password <pw> [--user admin] [--host localhost] [--stream-port 5551] [--management-port 15671]\n" +
        "  [--ca <ca.pem>] [--no-tls] [--vhost /] [--streams a,b] [--messages 1000] [--queues N] [--max-age 7D]\n" +
        "  --no-tls uses plain stream (5552) and HTTP management (15672) and ignores --ca; no value.\n" +
        "  --queues N declares N quorum queues in --vhost (none without the flag); messages are appended on every run."

/** Entry point of the developer seed tool (M2-12). Not part of the product; never prints the password. */
fun main(argv: Array<String>) {
    val args = try {
        SeedArgs.parse(argv.toList())
    } catch (e: SeedArgsException) {
        System.err.println("${e.message}\n$USAGE")
        exitProcess(2)
    }
    try {
        seed(args)
    } catch (e: Exception) {
        System.err.println("Seed failed: ${e::class.simpleName}: ${e.message}")
        caHint(e.message, args.tls)?.let { System.err.println(it) }
        exitProcess(1)
    }
}

private fun seed(args: SeedArgs) {
    ManagementClient(args).use { management ->
        if (args.queues > 0) {
            management.importDefinitions(ManyQueuesDefinitions.build(args.vhost, args.queues))
            println("Declared ${args.queues} quorum queues in vhost '${args.vhost}'.")
        }
        // Retention and publishing only apply when queues are not the only goal: a --queues run on the many vhost
        // has no use for the default streams (they live on "/").
        if (args.queues > 0 && args.vhost != "/") return
        args.retentionMaxAge?.let {
            management.setRetentionPolicy(args.vhost, args.streams, it)
            println("Set stream max-age ${it} on ${args.streams}.")
        }
    }
    if (args.messages == 0) return
    StreamPublisher(args).use { publisher ->
        for (stream in args.streams) {
            publisher.ensureStream(stream)
            println("Published ${publisher.publish(stream, args.messages)} messages to $stream.")
        }
    }
}

package dev.caerbannog.core.planner

/**
 * The planner's one read of what the broker has: names of exchanges, queues and streams of one virtual host, so the review graph can tell an
 * existing end from one that is nowhere. Read once when the planner starts and again when the plan's virtual host changes; never polled.
 * The real implementation makes list calls only (GET); `core.planner` never asks the broker itself.
 */
fun interface KnownTopologyReader {
    /** The names of [vhost], or null when the broker could not be read (a failed call, a virtual host the user cannot see). */
    suspend fun read(vhost: String): KnownTopology?
}

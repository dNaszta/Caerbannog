package dev.caerbannog.core.planner

import dev.caerbannog.core.topology.PolicyRow

/**
 * The planner's read of ONE policy of a virtual host, by name (M9-08, check notes). One item per call: there is no list method, so a note
 * cannot be built from "all policies". The real implementation sends `GET /api/policies/{vhost}/{name}` only; a 3.x connection never gets one.
 */
fun interface BrokerPolicyReader {
    suspend fun read(vhost: String, name: String): PolicyReadResult
}

/** What [BrokerPolicyReader.read] found. */
sealed interface PolicyReadResult {
    /** The policy, complete enough to judge (M9-14b: an incomplete definition is [Unreadable]). */
    data class Found(val row: PolicyRow) : PolicyReadResult

    /** No such policy, no permission, no answer or an incomplete row: the note is left out, never guessed. */
    data object Unreadable : PolicyReadResult
}

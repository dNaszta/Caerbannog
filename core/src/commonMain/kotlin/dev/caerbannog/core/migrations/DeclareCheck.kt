package dev.caerbannog.core.migrations

/**
 * What an action would meet on the broker right now. Read-only: an implementation sends GET requests only and never a path under a queue's
 * name, so it can never get, consume, acknowledge or purge a queue message.
 */
interface DeclareCheck {
    suspend fun find(action: MigrationAction): BrokerFinding
}

/** What the broker has for an action. A prediction only: the apply keeps its own read-back and conflict rule. */
sealed interface BrokerFinding {
    data object Absent : BrokerFinding
    data object Same : BrokerFinding
    data object Different : BrokerFinding

    /** [reason] is one of the executor's fixed texts ("the broker did not answer", "the user is not allowed to do this", ...), never the broker's body. */
    data class Unreadable(val reason: String) : BrokerFinding
}

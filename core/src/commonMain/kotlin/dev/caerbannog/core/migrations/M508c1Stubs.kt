package dev.caerbannog.core.migrations

// M5-08c1 STUBS (step 2, tests first). Only the TYPES the tests need to compile live here; the senior developer replaces this file
// with the real ones (move them next to their users). No behaviour is implemented in this file.

/** Why an action failed, decided by the executor from facts, never from text. */
enum class FailureKind { Other, Conflict }

/** One step of an apply: the action about to be sent (1-based [index] of [total] actions of migration [version]). */
data class ApplyStep(val version: String, val index: Int, val total: Int)

/** What the screen shows while an apply runs. */
data class ApplyProgress(val version: String, val index: Int, val total: Int)

/** How the last apply ended, when it did not succeed. */
sealed interface ApplyOutcome {
    data class Failed(val version: String, val index: Int, val total: Int, val reason: String) : ApplyOutcome
    data class Conflict(val version: String, val index: Int, val total: Int, val reason: String) : ApplyOutcome
    data class NotRecorded(val version: String, val reason: String) : ApplyOutcome
}

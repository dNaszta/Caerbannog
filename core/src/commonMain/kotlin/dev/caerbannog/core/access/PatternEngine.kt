package dev.caerbannog.core.access

/**
 * M10-04 contract (test developer, COMPILE-ONLY: the types are final, the behaviour behind them is built in step 3).
 * The regular-expression engine behind [AccessMatcher]: a small interface so `commonMain` holds no library. The JVM implementation (RE2/J, owner decision
 * 2026-11-27, OQ-1) sits in `jvmMain` behind [defaultPatternEngine]; the unit tests fake this interface to test the ORDER of the checks without a regex.
 */
interface PatternEngine {
    /** Reads [pattern]. A syntax error or a construct the engine does not support is [PatternCompilation.Rejected]; it never throws. */
    fun compile(pattern: String): PatternCompilation
}

sealed interface PatternCompilation {
    data class Compiled(val pattern: CompiledPattern) : PatternCompilation

    /** The engine does not read this pattern. The matcher answers [AccessAnswer.UnreadablePattern]; the reason is not kept (a pattern may hold a secret). */
    data object Rejected : PatternCompilation
}

fun interface CompiledPattern {
    /** The RabbitMQ rule: an UNANCHORED, case-sensitive search of the pattern in [name] (`^` and `$` anchor, nothing else does). */
    fun containsMatchIn(name: String): Boolean
}

/** The engine of this platform. JVM: RE2/J (step 3). */
expect fun defaultPatternEngine(): PatternEngine

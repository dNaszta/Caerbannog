package dev.caerbannog.core.access

/** M10-04 contract: which cell decided. Topic kinds apply to the topic permission row of the asked exchange. */
enum class PatternKind { Configure, Write, Read, TopicWrite, TopicRead }

/**
 * What is shown for a pattern: the text exactly as the broker returned it, or [PermissionsTexts.NO_PERMISSION] for the empty pattern. Never a hidden pattern (that is
 * unreadable, not shown). M10-04e: [noPermission] is true only for the empty pattern, so a real pattern spelled "no permission" is still a pattern (a text compare cannot tell them apart).
 */
data class DisplayPattern(val text: String, val noPermission: Boolean = false)

/** One cell that was checked: [kind], the [pattern] as shown, the topic permission's [exchange] (null for the other kinds) and the [checked] name the pattern was applied to. */
data class Hit(val kind: PatternKind, val pattern: DisplayPattern, val exchange: String?, val checked: String)

/** Why the permissions cannot be asked: the Permissions tab is locked, the user is a token user, or nothing is loaded. */
enum class PermissionsUnavailable { Locked, TokenUser, NotLoaded }

/** The input of the matcher: the loaded permissions as the M10-03 gateway returned them, or the reason there are none. */
sealed interface PermissionsSource {
    data class Available(val permissions: UserPermissions) : PermissionsSource

    data class NotAvailable(val reason: PermissionsUnavailable) : PermissionsSource
}

sealed interface AccessAnswer {
    /** Every needed cell matched; [hits] in the order of the action's table (publish to a topic exchange: Write, then TopicWrite). */
    data class WouldMatch(val hits: List<Hit>) : AccessAnswer

    /** The first needed cell that did not match. [noRowForVhost] is true when the user has no permission row on the vhost (PD-4: "<user> has no permission on <vhost>."). */
    data class NoMatch(val deciding: Hit, val noRowForVhost: Boolean = false) : AccessAnswer

    /** A needed cell is hidden, rejected by the engine, on [KnownDivergences], or over a limit; no half answer, nothing is guessed. [kind] is the first such cell. */
    data class UnreadablePattern(val kind: PatternKind) : AccessAnswer

    data class PermissionsNotReadable(val reason: PermissionsUnavailable) : AccessAnswer
}

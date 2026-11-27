package dev.caerbannog.core.access

import dev.caerbannog.core.connections.LinkState

/**
 * M10-04 part B contract (test developer; brief docs/tasks/M10-04-access-match-preview.md, items 6 to 8; mockup `docs/ux/Access.dc.html`, frames `perm-admin`,
 * `perm-verdicts`). What the form on the Permissions tab shows, all as display text. The words are the drawn ones (owner approved, AQ5, PD-3, PD-4).
 */
object MatchPreviewTexts {
    const val HEADING: String = "Can this user publish to or read from...?"
    const val ACTION_PUBLISH: String = "Publish to an exchange"
    const val ACTION_READ: String = "Read from a queue"
    const val ACTION_CONFIGURE: String = "Configure"
    const val VHOST_LABEL: String = "Virtual host"
    const val ROUTING_KEY_LABEL: String = "Routing key (optional)"
    const val CHECK: String = "Check"

    /** PD-3: the label of the name box per action. */
    const val NAME_PUBLISH: String = "Exchange"
    const val NAME_READ: String = "Queue"
    const val NAME_CONFIGURE: String = "Name"

    /** An empty exchange for publish means the default exchange; the box shows this after Check, never an empty box. */
    const val DEFAULT_EXCHANGE: String = "amq.default"

    const val TITLE_MATCH: String = "Would match."
    const val TITLE_MATCH_TOPIC: String = "Would match (topic permission)."
    const val TITLE_NO_MATCH: String = "Would not match."
    const val TITLE_UNREADABLE: String = "Pattern could not be read."
    const val TITLE_NOT_READABLE: String = "Permissions not readable."

    /** Where the patterns came from; shown with every verdict that applied patterns. */
    const val SOURCE_BROKER: String = "Patterns read from the broker."
    const val NOTE: String = "Prediction from the patterns above; the broker decides. Check sends nothing to the broker."
    const val LOCKED_SENTENCE: String = "Only an administrator can read the patterns of a user, so there is nothing to check against."
    const val NOT_LOADED_SENTENCE: String = "The patterns of this user have not been read from the broker, so there is nothing to check against."

    /** What is shown in place of a typed name or routing key that the guard hid. */
    const val ECHO_HIDDEN: String = SecretGuard.HIDDEN

    /**
     * The sentence of [answer] for the question ([name], [vhost], [routingKey] already passed through the guard; null or empty key reads as "the empty routing key").
     * Built only from the matcher's answer: the deciding check is named from `Hit.kind` (a `when` without `else`, so a new kind cannot be forgotten) and the typed
     * text comes from the parameters, never from the hit. [unreadablePattern] is what the form may show of an unreadable pattern; it defaults to the hidden marker
     * so a caller that forgets it can never show a hidden pattern's text.
     */
    fun sentence(
        answer: AccessAnswer,
        user: String,
        vhost: String,
        name: String,
        routingKey: String?,
        unreadablePattern: String = SecretGuard.HIDDEN,
    ): String = when (answer) {
        is AccessAnswer.WouldMatch -> {
            val hit = answer.hits.lastOrNull { it.kind.isTopic() } ?: answer.hits.firstOrNull()
            if (hit == null) "No pattern limits $name on $vhost." else hitSentence(hit, "matches", user, vhost, name, routingKey)
        }
        is AccessAnswer.NoMatch ->
            if (answer.noRowForVhost) "$user has no permission on $vhost." else hitSentence(answer.deciding, "does not match", user, vhost, name, routingKey)
        is AccessAnswer.UnreadablePattern ->
            "The ${kindWord(answer.kind)} pattern $unreadablePattern is not a pattern this app can apply. No answer is guessed."
        is AccessAnswer.PermissionsNotReadable -> when (answer.reason) {
            PermissionsUnavailable.Locked -> LOCKED_SENTENCE
            PermissionsUnavailable.TokenUser -> PermissionsTexts.tokenDetail(user)
            PermissionsUnavailable.NotLoaded -> NOT_LOADED_SENTENCE
        }
    }

    /** The title of [answer] as drawn; "(topic permission)" when a topic pattern took part in a match. */
    fun title(answer: AccessAnswer): String = when (answer) {
        is AccessAnswer.WouldMatch -> if (answer.hits.any { it.kind.isTopic() }) TITLE_MATCH_TOPIC else TITLE_MATCH
        is AccessAnswer.NoMatch -> TITLE_NO_MATCH
        is AccessAnswer.UnreadablePattern -> TITLE_UNREADABLE
        is AccessAnswer.PermissionsNotReadable -> TITLE_NOT_READABLE
    }

    /** The icon of [answer]. */
    fun icon(answer: AccessAnswer): VerdictIcon = when (answer) {
        is AccessAnswer.WouldMatch -> VerdictIcon.Match
        is AccessAnswer.NoMatch -> VerdictIcon.NoMatch
        is AccessAnswer.UnreadablePattern -> VerdictIcon.Unreadable
        is AccessAnswer.PermissionsNotReadable -> VerdictIcon.NotReadable
    }

    private fun hitSentence(hit: Hit, verb: String, user: String, vhost: String, name: String, routingKey: String?): String {
        // The flag, not the text: a real pattern spelled "no permission" is still a pattern.
        if (hit.pattern.noPermission) {
            return if (hit.kind.isTopic()) "$user has no ${kindWord(hit.kind)} permission on ${hit.exchange.orEmpty()}, so nothing is allowed."
            else "$user has no ${kindWord(hit.kind)} permission on $vhost, so nothing is allowed."
        }
        val pattern = "The ${kindWord(hit.kind)} pattern ${hit.pattern.text}"
        return if (hit.kind.isTopic()) {
            val key = if (routingKey.isNullOrEmpty()) "the empty routing key" else "the routing key $routingKey"
            "$pattern of ${hit.exchange.orEmpty()} $verb $key."
        } else {
            "$pattern $verb $name on $vhost."
        }
    }

    private fun PatternKind.isTopic(): Boolean = this == PatternKind.TopicWrite || this == PatternKind.TopicRead

    /** The word a pattern kind has in a sentence. */
    fun kindWord(kind: PatternKind): String = when (kind) {
        PatternKind.Configure -> "configure"
        PatternKind.Write -> "write"
        PatternKind.Read -> "read"
        PatternKind.TopicWrite -> "topic write"
        PatternKind.TopicRead -> "topic read"
    }
}

/** The three actions the form offers (PD-1). Bind and the stream forms are asked of the matcher directly. */
enum class MatchAction { Publish, Read, Configure }

/** The icon of a verdict: check_circle, block, help, lock in the mockup. */
enum class VerdictIcon { Match, NoMatch, Unreadable, NotReadable }

/**
 * One verdict as drawn: [icon], [title] (one of the `MatchPreviewTexts.TITLE_*`), the [sentence] naming pattern, kind, name and virtual host, [source] (null when no
 * pattern was applied: locked, token, not loaded) and the [note] (always [MatchPreviewTexts.NOTE]). Nothing in it is a typed secret or a hidden pattern's text.
 */
data class MatchVerdictView(val icon: VerdictIcon, val title: String, val sentence: String, val source: String?, val note: String)

/**
 * The form. [vhosts] are the hosts of the shown table, unfiltered; [vhost] is the chosen one (the first host until chosen; null when there is none).
 * [name] is what the box shows: after Check of a publish with an empty exchange it is [MatchPreviewTexts.DEFAULT_EXCHANGE]. [routingKey] is drawn only when [showRoutingKey].
 * [verdict] is null before the first Check and after every change of the chosen user, the shown permissions or the connection, and (R3) after any real change of
 * action, host, name or routing key (typed text is kept; the amq.default echo written by Check itself does not clear it). Locked and TokenUser: derived, see [checkEnabled].
 */
data class MatchPreviewState(
    val action: MatchAction = MatchAction.Publish,
    val vhosts: List<String> = emptyList(),
    val vhost: String? = null,
    val nameLabel: String = MatchPreviewTexts.NAME_PUBLISH,
    val name: String = "",
    val showRoutingKey: Boolean = true,
    val routingKey: String = "",
    val verdict: MatchVerdictView? = null,
    /** False for Locked and TokenUser (R1): the form is drawn dimmed, Check is disabled, the verdict "Permissions not readable." is derived from the input and `check()` does nothing. */
    val checkEnabled: Boolean = true,
)

/**
 * What the form checks against, read-only, from [PermissionsViewModel.matchInput]: the chosen [user], the [source] (Available with the rows the broker returned,
 * also while they are stale; Locked, TokenUser, or NotLoaded while loading, failed or not connected), the [vhosts] of the table (broker rows plus listed hosts, never
 * filtered by the vhost filter, a hidden host name left out; for Locked and TokenUser exactly `listOf(profile.virtualHost)`, display only; empty for NotLoaded) and the [link] state. Two equal values mean nothing the verdict depends on changed (a refresh that
 * returns the same patterns emits no change).
 */
data class MatchInput(val user: String?, val source: PermissionsSource, val vhosts: List<String>, val link: LinkState)

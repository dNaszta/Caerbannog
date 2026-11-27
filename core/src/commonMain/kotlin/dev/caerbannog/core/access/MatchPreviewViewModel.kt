package dev.caerbannog.core.access

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State holder of the match preview form on the Permissions tab (M10-04 part B; brief items 6 to 8). Every method hops onto [scope], like [PermissionsViewModel].
 * Pressing Check calls [matcher] only: no gateway, no request. [heldSecret] is read per call and never stored; typed text passes [SecretGuard] with it before it
 * is echoed in a verdict.
 *
 * Rules: Locked and token users have a derived verdict and a disabled Check (R1); a change of user, patterns or link clears the verdict; a real edit of action,
 * host, name or routing key clears it too (R3), except the `amq.default` echo that Check writes itself; typed text is always kept.
 */
class MatchPreviewViewModel(
    private val input: StateFlow<MatchInput>,
    private val scope: CoroutineScope,
    private val heldSecret: () -> String? = { null },
    private val matcher: AccessMatcher = AccessMatcher(),
) {
    private var current: MatchInput = input.value
    private var action: MatchAction = MatchAction.Publish
    private var chosenVhost: String? = null
    private var name: String = ""
    private var routingKey: String = ""
    private var verdict: MatchVerdictView? = null

    private val _state = MutableStateFlow(render())
    val state: StateFlow<MatchPreviewState> = _state.asStateFlow()

    init {
        scope.launch { input.collect(::onInput) }
    }

    fun chooseAction(action: MatchAction) = onScope {
        if (action != this.action) edited { this.action = action }
    }

    fun chooseVhost(vhost: String) = onScope {
        if (vhost in current.vhosts && vhost != effectiveVhost()) edited { chosenVhost = vhost }
    }

    fun setName(text: String) = onScope {
        if (text != name) edited { name = text }
    }

    fun setRoutingKey(text: String) = onScope {
        if (text != routingKey) edited { routingKey = text }
    }

    /** Asks the pure matcher with what the form holds and shows the answer. Does nothing when Check is disabled, or when there is no host to ask about. */
    fun check() = onScope {
        if (!checkEnabled()) return@onScope
        val source = current.source
        if (source is PermissionsSource.Available && effectiveVhost() == null) return@onScope
        val vhost = effectiveVhost().orEmpty()
        val asked = if (action == MatchAction.Publish && name.isEmpty()) MatchPreviewTexts.DEFAULT_EXCHANGE else name
        val question = AccessQuestion(accessAction(), vhost, asked, routingKey.takeIf { action == MatchAction.Publish })
        // The echo of the default exchange is written into the box by Check itself and must not count as an edit.
        name = asked
        verdict = verdictOf(matcher.check(source, question), source, vhost, asked)
        publish()
    }

    private fun onInput(next: MatchInput) {
        val before = effectiveVhost()
        val changed = next.user != current.user || next.source != current.source || next.link != current.link
        current = next
        if (changed || effectiveVhost() != before) verdict = null
        publish()
    }

    private fun onScope(block: () -> Unit) {
        scope.launch { block() }
    }

    private inline fun edited(change: () -> Unit) {
        change()
        verdict = null
        publish()
    }

    private fun accessAction(): AccessAction = when (action) {
        MatchAction.Publish -> AccessAction.PublishToExchange
        MatchAction.Read -> AccessAction.ReadFromQueue
        MatchAction.Configure -> AccessAction.Configure
    }

    private fun checkEnabled(): Boolean = (current.source as? PermissionsSource.NotAvailable)?.reason.let {
        it != PermissionsUnavailable.Locked && it != PermissionsUnavailable.TokenUser
    }

    private fun effectiveVhost(): String? = chosenVhost?.takeIf { it in current.vhosts } ?: current.vhosts.firstOrNull()

    private fun verdictOf(answer: AccessAnswer, source: PermissionsSource, vhost: String, asked: String): MatchVerdictView {
        val secret = heldSecret()
        val shownName = SecretGuard.redact(asked, secret).text
        val shownKey = SecretGuard.redact(routingKey, secret).text
        val sentence = MatchPreviewTexts.sentence(
            answer = answer,
            user = current.user.orEmpty(),
            vhost = vhost,
            name = shownName,
            routingKey = shownKey.takeIf { action == MatchAction.Publish },
            unreadablePattern = (answer as? AccessAnswer.UnreadablePattern)?.let { unreadableText(source, vhost, asked, it.kind) } ?: SecretGuard.HIDDEN,
        )
        return MatchVerdictView(
            icon = MatchPreviewTexts.icon(answer),
            title = MatchPreviewTexts.title(answer),
            sentence = sentence,
            source = MatchPreviewTexts.SOURCE_BROKER.takeUnless { answer is AccessAnswer.PermissionsNotReadable },
            note = MatchPreviewTexts.NOTE,
        )
    }

    // A hidden cell is never shown as text; any other unreadable pattern is shown as the broker returned it (the gateway already guarded it).
    private fun unreadableText(source: PermissionsSource, vhost: String, asked: String, kind: PatternKind): String {
        val permissions = (source as? PermissionsSource.Available)?.permissions ?: return SecretGuard.HIDDEN
        val row = permissions.rows.firstOrNull { it.vhost == vhost }
        val cell = when (kind) {
            PatternKind.Configure -> row?.configure
            PatternKind.Write -> row?.write
            PatternKind.Read -> row?.read
            PatternKind.TopicWrite -> permissions.topicRows.firstOrNull { it.vhost == vhost && it.exchange == asked }?.write
            PatternKind.TopicRead -> permissions.topicRows.firstOrNull { it.vhost == vhost && it.exchange == asked }?.read
        }
        return cell?.let(PermissionsTexts::shown) ?: SecretGuard.HIDDEN
    }

    private fun derivedVerdict(): MatchVerdictView? {
        val reason = (current.source as? PermissionsSource.NotAvailable)?.reason ?: return null
        if (reason == PermissionsUnavailable.NotLoaded) return null
        val answer = AccessAnswer.PermissionsNotReadable(reason)
        return MatchVerdictView(
            icon = MatchPreviewTexts.icon(answer),
            title = MatchPreviewTexts.title(answer),
            sentence = MatchPreviewTexts.sentence(answer, current.user.orEmpty(), effectiveVhost().orEmpty(), "", null),
            source = null,
            note = MatchPreviewTexts.NOTE,
        )
    }

    private fun publish() {
        _state.value = render()
    }

    private fun render(): MatchPreviewState = MatchPreviewState(
        action = action,
        vhosts = current.vhosts,
        vhost = effectiveVhost(),
        nameLabel = when (action) {
            MatchAction.Publish -> MatchPreviewTexts.NAME_PUBLISH
            MatchAction.Read -> MatchPreviewTexts.NAME_READ
            MatchAction.Configure -> MatchPreviewTexts.NAME_CONFIGURE
        },
        name = name,
        showRoutingKey = action == MatchAction.Publish,
        routingKey = routingKey,
        verdict = if (checkEnabled()) verdict else derivedVerdict(),
        checkEnabled = checkEnabled(),
    )
}

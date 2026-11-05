package dev.caerbannog.ui.streams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import dev.caerbannog.core.streams.ConsumerRowState
import dev.caerbannog.core.streams.ConsumerStatus
import dev.caerbannog.core.streams.PublisherRowState
import dev.caerbannog.core.streams.StreamClientsScreen
import dev.caerbannog.core.streams.StreamClientsState
import dev.caerbannog.core.streams.StreamClientsTexts
import dev.caerbannog.ui.appframe.AppDestination

/** M2-06b shared builders: states as the view model builds them (display strings only), and the event recorder. */
internal class RecordedClientsEvents : StreamClientsEvents {
    val calls = mutableListOf<String>()

    /** The calls without the show/hide bookkeeping, for tests about what the user pressed. */
    fun sent(): List<String> = calls.filterNot { it.startsWith("show:") || it == "hide" }
    override fun show(stream: String) { calls += "show:$stream" }
    override fun hide() { calls += "hide" }
    override fun refreshNow() { calls += "refreshNow" }
    override fun retry() { calls += "retry" }
    override fun back() { calls += "back" }
}

internal const val STREAM = "orders-events"

/** The mockup's rows (`Clients.dc.html`): connection `ip:port -> ip:port`, user under it. */
internal fun publisher(
    reference: String = "checkout-1",
    connection: String = "10.0.4.21:51234 -> 10.0.0.5:5551",
    user: String = "checkout",
    published: String = "48,100",
    confirmed: String = "48,100",
    errors: String = "0",
    settling: Boolean = false,
    key: String = "$connection#$reference",
) = PublisherRowState(key, connection, user, reference, published, confirmed, errors, settling)

internal fun consumer(
    key: String = "billing",
    name: String = "billing",
    connection: String = "10.0.5.14:38812 -> 10.0.0.5:5551",
    user: String = "billing",
    offset: String = "48299",
    behind: String = "0",
    status: ConsumerStatus = ConsumerStatus.CaughtUp,
    browseFromOffset: Long? = 48299,
) = ConsumerRowState(key, name, connection, user, offset, behind, status, browseFromOffset)

internal val settlingPublisher = publisher(
    reference = "backfill-job", connection = StreamClientsTexts.UNKNOWN, user = StreamClientsTexts.NO_USER,
    published = StreamClientsTexts.UPDATING, confirmed = StreamClientsTexts.UPDATING, errors = StreamClientsTexts.UPDATING, settling = true,
)
internal val settlingConsumer = consumer(
    key = "new", name = StreamClientsTexts.UNNAMED, connection = StreamClientsTexts.UNKNOWN, user = StreamClientsTexts.NO_USER,
    offset = StreamClientsTexts.UPDATING, behind = StreamClientsTexts.UPDATING, status = ConsumerStatus.Updating, browseFromOffset = null,
)

internal val mockupPublishers = listOf(publisher(), publisher("backfill-job", "10.0.7.8:40022 -> 10.0.0.5:5551", StreamClientsTexts.NO_USER, "200", "200", "0"))
internal val mockupConsumers = listOf(
    consumer(),
    consumer("analytics", "analytics", "10.0.9.3:52901 -> 10.0.0.5:5551", "analytics", "47950", "349", ConsumerStatus.Behind(349), 47950),
    consumer("audit", "audit", "10.0.6.30:44417 -> 10.0.0.5:5551", "audit", "48100", "199", ConsumerStatus.Behind(199), 48100),
)

/** The state of the Content screen (message null, sections drawn). */
internal fun content(
    publishers: List<PublisherRowState> = mockupPublishers,
    consumers: List<ConsumerRowState> = mockupConsumers,
    lastOffset: String = "48299",
    notice: String? = null,
    stale: Boolean = false,
    banner: String? = null,
    updatedAtMillis: Long? = null,
) = StreamClientsState(
    screen = StreamClientsScreen.Content, message = null, stream = STREAM, lastOffset = lastOffset, publishers = publishers, consumers = consumers,
    ownConnectionsNotice = notice, stale = stale, banner = banner, updatedAtMillis = updatedAtMillis,
)

/** A state without sections, with the message the view model gives that screen. */
internal fun messageOnly(screen: StreamClientsScreen, message: String?, lastOffset: String = "") =
    StreamClientsState(screen = screen, message = message, stream = STREAM, lastOffset = lastOffset)

internal val clientsNotConnected = messageOnly(StreamClientsScreen.NotConnected, StreamClientsTexts.NOT_CONNECTED)
internal val clientsConnecting = messageOnly(StreamClientsScreen.Connecting, StreamClientsTexts.CONNECTING)
internal val clientsLoading = messageOnly(StreamClientsScreen.Loading, StreamClientsTexts.LOADING)

/** The literals of the brief and the mockup, not the constants, so a rewording is a visible change. */
internal const val NOTICE_TEXT = "Showing only your own connections. The monitoring tag shows all."
internal const val PLUGIN_TEXT = "The stream management plugin is not enabled. Enable rabbitmq_stream_management on the broker."
internal const val GONE_TEXT = "This stream no longer exists."
internal const val FAILED_TEXT = "Publishers and consumers could not be loaded."
internal const val DENIED_CLIENTS_TEXT = "This user cannot read stream statistics. Ask for the monitoring tag."
internal const val HINT_TEXT = "Stored offsets count as messages, so a consumer that stores its offset can read a few behind when it has read everything."

/** What the screen is showing now; assign to change it in a running test (then `waitForIdle()`). */
internal class ShownClients(state: StreamClientsState, now: Long, val events: RecordedClientsEvents) {
    var state by mutableStateOf(state)
    var now by mutableStateOf(now)
}

/** Sets the content to the stream screen once; change it later through the returned [ShownClients]. Tags: see [StreamScreenUiTest]. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.clientsScreen(
    state: StreamClientsState,
    events: RecordedClientsEvents = RecordedClientsEvents(),
    nowMillis: Long = 0,
    stream: String = STREAM,
    onNavigate: (AppDestination) -> Unit = {},
    wrap: @Composable (@Composable () -> Unit) -> Unit = { it() },
): ShownClients {
    val shown = ShownClients(state, nowMillis, events)
    setContent {
        wrap {
            StreamScreen(stream, shown.state, shown.now, events, connected, noControlEvents, onNavigate)
        }
    }
    return shown
}

internal fun SemanticsNodeInteraction.stateDescription(): String? = fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
internal fun SemanticsNodeInteraction.contentDescription(): String? =
    fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")

/** The text node of a consumer cell: `consumerCell:<key>:<Name|Connection|User|Offset|Behind|Status>`. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.consumerCell(key: String, column: String) = onNodeWithTag("consumerCell:$key:$column", useUnmergedTree = true)

/** The text node of a publisher cell, by list position (publishers have no key): `publisherCell:<index>:<Reference|Connection|User|Published|Confirmed|Errors>`. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.publisherCell(index: Int, column: String) = onNodeWithTag("publisherCell:$index:$column", useUnmergedTree = true)

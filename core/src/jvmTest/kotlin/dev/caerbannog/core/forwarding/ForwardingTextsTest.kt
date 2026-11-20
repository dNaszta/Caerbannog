package dev.caerbannog.core.forwarding

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.forwarding.fakes.FakeShovelGateway
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** M8-09: the exact wording the owner approved (decisions D2 and D3 of the brief), and the failure texts that moved from `ForwardingService` to `ForwardingTexts`. */
class ForwardingTextsTest {
    private val ref = ShovelRef("/", "caerbannog-migrate-orders")

    @Test
    fun `the six block texts are the approved wording`() {
        fun b(block: ForwardingBlock) = ForwardingTexts.block(block, "new-eu", "orders")
        assertEquals("Topology changes are locked on new-eu: forwarding needs the level Declare and edit and the configure permission.", b(ForwardingBlock.TopologyLocked))
        assertEquals("The user of new-eu needs the policymaker tag to create a shovel.", b(ForwardingBlock.NoPolicymakerTag))
        assertEquals(
            "The user of new-eu cannot read the shovel status and needs the monitoring tag. Without it the app could not see a failed shovel or remove it.",
            b(ForwardingBlock.CannotReadStatus),
        )
        assertEquals("The shovel plugins are not enabled on new-eu. Enable rabbitmq_shovel and rabbitmq_shovel_management.", b(ForwardingBlock.ShovelPluginsOff))
        assertEquals("The queue orders does not exist on new-eu. Apply the migration that declares it first.", b(ForwardingBlock.DestinationMissing))
        val status = ShovelStatus(ref, ShovelRunState.RUNNING, null, 40, 80)
        assertEquals("A shovel named caerbannog-migrate-orders already exists on new-eu (running, 40 forwarded). The app never replaces a shovel.", b(ForwardingBlock.ShovelExists(status)))
    }

    @Test
    fun `the warnings are the approved wording`() {
        assertEquals("The destination queue is a classic queue, not a quorum queue.", ForwardingTexts.warning(ForwardingWarning.DestinationNotQuorumOrStream(DestinationQueueType.CLASSIC)))
        assertEquals(ForwardingTexts.KEEPS_RUNNING, ForwardingTexts.warning(ForwardingWarning.KeepsShovelAndCredentials))
        assertEquals(ForwardingTexts.NOT_ENCRYPTED, ForwardingTexts.warning(ForwardingWarning.NotEncrypted("legacy-eu")))
    }

    @Test
    fun `the progress and end texts`() {
        assertEquals("Moved about 40 of 120 messages", ForwardingTexts.moved(40, 120))
        assertEquals("Moved 40 messages; the shovel keeps running", ForwardingTexts.movedKeepRunning(40))
        assertEquals("Finished. About 20 messages stayed on the source because they were published meanwhile", ForwardingTexts.finishedStayed(20))
        assertEquals("Finished. No messages stayed on the source", ForwardingTexts.FINISHED_NONE_STAYED)
        assertEquals("Finished.", ForwardingTexts.FINISHED)
        assertEquals("The forwarding failed: failed to connect to source", ForwardingTexts.failed("failed to connect to source"))
        assertEquals("The shovel was removed, so no stored credentials remain.", ForwardingTexts.CLEANED_UP)
        assertEquals(
            "The shovel could not be removed here. It and its stored password remain on new-eu; a user with the level Full control must remove it.",
            ForwardingTexts.notCleanedUp("new-eu"),
        )
    }

    @Test
    fun `the disabled reasons and the labels`() {
        assertEquals("Connect to a RabbitMQ 4.x broker first", ForwardingTexts.CONNECT_FIRST)
        assertEquals("Removing a shovel needs Full control", ForwardingTexts.NEEDS_FULL_CONTROL)
        assertEquals("Forward messages", ForwardingTexts.BUTTON_FORWARD)
        assertEquals("Type the source queue name to confirm", ForwardingTexts.LABEL_TYPED)
        assertEquals("Use another user for this Shovel", ForwardingTexts.LABEL_ANOTHER_USER)
        assertEquals("Keep the shovel running after the messages are moved", ForwardingTexts.LABEL_KEEP_RUNNING)
        assertEquals("AMQP host and port as new-eu reaches them", ForwardingTexts.amqpLabel("new-eu"))
    }

    @Test
    fun `each failed dry run has its own fixed text, and none holds a url or a body`() {
        val texts = listOf(
            DryRunOutcome.SourceUnreachable("legacy-eu"), DryRunOutcome.SourceNotPermitted, DryRunOutcome.DestinationUnreachable, DryRunOutcome.Failed("raw broker text amqp://u:pw@h"),
        ).map { ForwardingTexts.probeFailed(it) }
        assertTrue(texts.all { it.isNotBlank() }, "empty probe text: $texts")
        assertEquals(texts.size, texts.toSet().size, "two outcomes share a text: $texts")
        assertTrue(texts.none { it.contains("pw") || it.contains("amqp://") }, "the broker's own text leaked: $texts")
        assertTrue(texts[0].contains("legacy-eu"))
    }

    @Test
    fun `the failure texts of the service live in ForwardingTexts and the service uses them`() = runTest {
        assertEquals("The broker could not be reached", ForwardingTexts.BROKER_UNREACHABLE)
        assertEquals("The broker did not allow creating the shovel", ForwardingTexts.CREATE_NOT_ALLOWED)
        assertEquals("The broker did not allow removing the shovel", ForwardingTexts.REMOVE_NOT_ALLOWED)
        val request = Fwd.request()
        fun service(g: FakeShovelGateway, level: TopologyControlLevel = TopologyControlLevel.FULL_CONTROL) = ForwardingService(g) { Fwd.context(level) }
        assertEquals(StartResult.Failed(ForwardingTexts.CREATE_NOT_ALLOWED), service(Fwd.gateway().apply { startOutcome(StartOutcome.NotPermitted) }).start(request, "orders"))
        assertEquals(StartResult.Failed(ForwardingTexts.BROKER_UNREACHABLE), service(Fwd.gateway().apply { startOutcome(StartOutcome.Unreachable) }).start(request, "orders"))
        assertEquals(RemoveResult.Failed(ForwardingTexts.REMOVE_NOT_ALLOWED), service(Fwd.gateway().apply { removeOutcome(RemoveOutcome.NotPermitted) }).remove(ref))
        assertEquals(RemoveResult.Failed(ForwardingTexts.BROKER_UNREACHABLE), service(Fwd.gateway().apply { removeOutcome(RemoveOutcome.Unreachable) }).remove(ref))
        assertNotEquals("", ForwardingTexts.BROKER_UNREACHABLE)
    }

    @Test
    fun `M8-15 the direct destination block text is the approved wording and carries no broker text`() {
        val text = ForwardingTexts.block(ForwardingBlock.DirectDestinationNeedsAdministrator, "new-eu", "orders")
        assertEquals(
            "RabbitMQ 4.4.1 and later lets only an administrator create a shovel that sends to its own broker. The user of new-eu is not an administrator. " +
                "Use an administrator user for this connection, or ask the administrator of new-eu to create the shovel.",
            text,
        )
        for (leak in listOf("hostname", "amqp", "400", "Validation", "password")) assertTrue(!text.contains(leak, ignoreCase = true), "the text contains \"$leak\": $text")
    }
}

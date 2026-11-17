package dev.caerbannog.ui.planner

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.planner.DeadLetterForm
import dev.caerbannog.core.planner.DeadLetterStrategy
import dev.caerbannog.core.planner.DeadLetterTarget
import dev.caerbannog.core.planner.KnownQueue
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.WizardState
import dev.caerbannog.core.planner.fakes.FakeBrokerPolicyReader
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import dev.caerbannog.core.topology.PolicyRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M9-09 step 2, written first: the "Dead-lettering for a queue" card and its form in the wizard (mockup "4. Dead-lettering"), 1280 x 800. RED until `DeadLetterFormView` exists.
 * The tag contract is the brief's: `planWizard:deadLetter:` + `queue`, `targetQueue`, `targetStream`, `exchange`, `targetName`, `strategyAtMostOnce`, `strategyAtLeastOnce`,
 * `refusedTick`, `notes`; the card is `planWizard:kind:Dead-lettering for a queue`; a field problem is `planForm:error:<field>` with the field names of `DeadLetterForm.problems`
 * (`queue`, `exchange`, `targetName`, `refusedTick`). Beyond the brief and pinned here (the architect confirms): the queue, exchange and target fields are editable text
 * fields that hold the name (the suggestion when not typed); the two targets and the two strategies are selectable (`SemanticsProperties.Selected`); the tick is a toggle
 * (`ToggleableState`); the strategy options AND the tick are drawn only when `DeadLetterForm.offersAtLeastOnce` (a known quorum queue). Sentences are looked up by their text
 * anywhere on the page, so the test does not pin which tag holds them, except the policy note (`planWizard:deadLetter:notes`, the brief). The dashed line of the edge is a
 * drawing detail no semantics tree carries: it is looked at in step 4.
 */
@OptIn(ExperimentalTestApi::class)
class DeadLetterFormUiTest {
    private val kind = "Dead-lettering for a queue"
    private val tick = "Publishers are refused when the queue is full"

    private val broker = KnownTopology(
        queues = setOf("orders", "legacy", "invoices"),
        streams = setOf("telemetry"),
        queueDetails = mapOf(
            "orders" to KnownQueue("quorum", null), "legacy" to KnownQueue("classic", null), "invoices" to KnownQueue("quorum", "limits"),
            "telemetry" to KnownQueue("stream", null),
        ),
    )

    private fun UiRig.form() = assertIs<DeadLetterForm>(assertIs<WizardState.Details>(state.wizard).form)

    private fun DesktopComposeUiTest.toForm(r: UiRig) {
        showPlanner(r)
        node("planner:addStepBtn").performClick()
        node("planWizard:kind:$kind").performClick()
        node("planWizard:next").performClick()
    }

    private fun DesktopComposeUiTest.fieldText(tag: String) =
        node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun DesktopComposeUiTest.count(tag: String) =
        onAllNodes(androidx.compose.ui.test.SemanticsMatcher("tag is $tag") { it.config.getOrNull(SemanticsProperties.TestTag) == tag }, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun DesktopComposeUiTest.sentenceCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun DesktopComposeUiTest.selected(tag: String) = node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected)

    private fun DesktopComposeUiTest.ticked(): Boolean =
        node("planWizard:deadLetter:refusedTick").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On

    private fun DesktopComposeUiTest.typeQueue(name: String) {
        node("planWizard:deadLetter:queue").performTextReplacement(name)
    }

    private fun rigWithBroker(policies: dev.caerbannog.core.planner.BrokerPolicyReader? = null, known: KnownTopology = broker, prepare: UiRig.() -> Unit = {}) =
        UiRig(reader = FakeKnownTopologyReader(known), policies = policies, prepare = prepare)

    // ---- page 1 ----

    @Test
    fun `page 1 draws the card after Policy and before MQTT route with its line, and Next sends the kind`() = runDesktopComposeUiTest(1280, 800) {
        val r = rigWithBroker()
        showPlanner(r)
        node("planner:addStepBtn").performClick()
        node("planWizard:kind:$kind").assertIsDisplayed()
        assertTrue(textOf("planWizard:kind:$kind").contains("Exchange, dead-letter queue, binding and policy"), textOf("planWizard:kind:$kind"))
        val y = { k: String -> node("planWizard:kind:$k").fetchSemanticsNode().positionInRoot.y }
        assertTrue(y("Policy") < y(kind) && y(kind) < y("MQTT route"), "the card sits between Policy and MQTT route")
        node("planWizard:kind:$kind").performClick()
        node("planWizard:next").performClick()
        assertTrue(r.events.calls.contains("chooseKind:$kind"), r.events.calls.toString())
        assertEquals(DeadLetterForm(queue = ""), r.form())
        r.close()
    }

    // ---- page 2: the empty state ----

    @Test
    fun `the form opens with no queue, at most once, a dead-letter queue, and Next disabled with the reason under the queue`() = runDesktopComposeUiTest(1280, 800) {
        val r = rigWithBroker()
        toForm(r)
        listOf("queue", "targetQueue", "targetStream", "exchange", "targetName", "strategyAtMostOnce").forEach { node("planWizard:deadLetter:$it").assertIsDisplayed() }
        assertEquals(PlannerTexts.DEAD_LETTER_QUEUE_MISSING, textOf("planForm:error:queue"))
        assertEquals("Choose or type the queue", textOf("planForm:error:queue"))
        node("planWizard:next").assertIsNotEnabled()
        assertEquals(true, selected("planWizard:deadLetter:targetQueue"))
        assertEquals(true, selected("planWizard:deadLetter:strategyAtMostOnce"))
        assertTrue(textOf("planWizard:deadLetter:targetQueue").contains("A dead-letter queue"), textOf("planWizard:deadLetter:targetQueue"))
        assertTrue(textOf("planWizard:deadLetter:targetStream").contains("A stream"), textOf("planWizard:deadLetter:targetStream"))
        assertTrue(textOf("planWizard:deadLetter:strategyAtMostOnce").contains("At most once"), textOf("planWizard:deadLetter:strategyAtMostOnce"))
        assertEquals("", fieldText("planWizard:deadLetter:exchange"), "no queue, no suggestion")
        assertEquals(0, count("planWizard:deadLetter:strategyAtLeastOnce"), "no queue chosen: At least once is not offered")
        assertEquals(0, count("planWizard:deadLetter:refusedTick"))
        r.close()
    }

    @Test
    fun `the default note is drawn with At most once, word for word`() = runDesktopComposeUiTest(1280, 800) {
        val r = rigWithBroker()
        toForm(r)
        typeQueue("orders")
        assertTrue(sentenceCount("If the dead-letter target cannot take a message, RabbitMQ drops it.") >= 1)
        r.close()
    }

    // ---- choosing the queue ----

    @Test
    fun `typing a queue sends the form, suggests both names, offers At least once for a quorum queue and enables Next`() = runDesktopComposeUiTest(1280, 800) {
        val r = rigWithBroker()
        toForm(r)
        typeQueue("orders")
        assertEquals(DeadLetterForm(queue = "orders"), r.form())
        assertEquals("orders-dlx", fieldText("planWizard:deadLetter:exchange"))
        assertEquals("orders-dlq", fieldText("planWizard:deadLetter:targetName"))
        node("planWizard:deadLetter:strategyAtLeastOnce").assertIsDisplayed()
        assertEquals(0, count("planForm:error:queue"))
        node("planWizard:next").assertIsEnabled()
        r.close()
    }

    @Test
    fun `the suggested names follow the queue until they are typed`() = runDesktopComposeUiTest(1280, 800) {
        val r = rigWithBroker()
        toForm(r)
        typeQueue("orders")
        typeQueue("invoices")
        assertEquals("invoices-dlx", fieldText("planWizard:deadLetter:exchange"))
        node("planWizard:deadLetter:exchange").performTextReplacement("my-dlx")
        assertEquals("my-dlx", r.form().exchangeName)
        assertEquals(null, r.form().targetName, "the other name still follows")
        typeQueue("orders")
        assertEquals("my-dlx", fieldText("planWizard:deadLetter:exchange"), "a typed name stays")
        assertEquals("orders-dlq", fieldText("planWizard:deadLetter:targetName"))
        r.close()
    }

    @Test
    fun `a typed empty name is its own problem under its field, and Next is disabled`() = runDesktopComposeUiTest(1280, 800) {
        val r = rigWithBroker()
        toForm(r)
        typeQueue("orders")
        node("planWizard:deadLetter:exchange").performTextReplacement("")
        assertEquals(PlannerTexts.EXCHANGE_NAME_MISSING, textOf("planForm:error:exchange"))
        node("planWizard:next").assertIsNotEnabled()
        node("planWizard:deadLetter:targetName").performTextReplacement("")
        assertEquals(PlannerTexts.QUEUE_NAME_MISSING, textOf("planForm:error:targetName"))
        r.close()
    }

    @Test
    fun `a stream as the source is refused under the queue field`() = runDesktopComposeUiTest(1280, 800) {
        val r = rigWithBroker()
        toForm(r)
        typeQueue("telemetry")
        assertEquals("Dead-lettering does not work for a stream. Choose a queue.", textOf("planForm:error:queue"))
        node("planWizard:next").assertIsNotEnabled()
        r.close()
    }

    @Test
    fun `a name nobody knows is allowed, without At least once`() = runDesktopComposeUiTest(1280, 800) {
        val r = rigWithBroker()
        toForm(r)
        typeQueue("unlisted")
        node("planWizard:next").assertIsEnabled()
        assertEquals(0, count("planWizard:deadLetter:strategyAtLeastOnce"))
        r.close()
    }

    @Test
    fun `no broker read is drawn without At least once, without a note and with Next enabled for a typed queue`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toForm(r)
        typeQueue("orders")
        node("planWizard:next").assertIsEnabled()
        assertEquals(0, count("planWizard:deadLetter:strategyAtLeastOnce"))
        assertEquals(0, count("planWizard:deadLetter:refusedTick"))
        assertEquals(0, sentenceCount("names its own dead-letter exchange"))
        assertEquals(0, sentenceCount("Only one policy applies"))
        r.close()
    }

    @Test
    fun `At least once is not offered for a classic queue and the policy stays plain`() = runDesktopComposeUiTest(1280, 800) {
        val r = rigWithBroker()
        toForm(r)
        typeQueue("legacy")
        assertEquals(0, count("planWizard:deadLetter:strategyAtLeastOnce"))
        assertEquals(0, count("planWizard:deadLetter:refusedTick"))
        node("planWizard:deadLetter:strategyAtMostOnce").assertIsDisplayed()
        r.close()
    }

    // ---- the target ----

    @Test
    fun `choosing a stream sends the target, and the name field is a stream's`() = runDesktopComposeUiTest(1280, 800) {
        val r = rigWithBroker()
        toForm(r)
        typeQueue("orders")
        node("planWizard:deadLetter:targetStream").performClick()
        assertEquals(DeadLetterTarget.Stream, r.form().target)
        assertEquals(true, selected("planWizard:deadLetter:targetStream"))
        assertEquals(false, selected("planWizard:deadLetter:targetQueue"))
        assertEquals("orders-dlq", fieldText("planWizard:deadLetter:targetName"))
        node("planWizard:deadLetter:targetName").performTextReplacement("")
        assertEquals(PlannerTexts.STREAM_NAME_MISSING, textOf("planForm:error:targetName"))
        r.close()
    }

    // ---- at least once ----

    @Test
    fun `At least once needs the tick, Next is disabled with the reason, the two notes are drawn, the tick enables Next`() = runDesktopComposeUiTest(1280, 800) {
        val r = rigWithBroker()
        toForm(r)
        typeQueue("orders")
        node("planWizard:deadLetter:strategyAtLeastOnce").performClick()
        assertEquals(DeadLetterStrategy.AtLeastOnce, r.form().strategy)
        assertEquals(true, selected("planWizard:deadLetter:strategyAtLeastOnce"))
        node("planWizard:deadLetter:refusedTick").assertIsDisplayed()
        assertTrue(textOf("planWizard:deadLetter:refusedTick").contains(tick), textOf("planWizard:deadLetter:refusedTick"))
        assertEquals("""Tick "Publishers are refused when the queue is full" to use At least once""", textOf("planForm:error:refusedTick"))
        node("planWizard:next").assertIsNotEnabled()
        assertTrue(sentenceCount("RabbitMQ keeps a message it cannot pass on and retries. Retries can take minutes, so do not rely on a quick delivery.") >= 1)
        assertTrue(sentenceCount("While the queue is full, publishers are refused. A quorum queue holds one message more than its limit.") >= 1)
        assertEquals(0, sentenceCount("If the dead-letter target cannot take a message, RabbitMQ drops it."), "the default note is for At most once")
        node("planWizard:deadLetter:refusedTick").performClick()
        assertTrue(ticked())
        assertTrue(r.form().publishersRefusedAccepted)
        assertEquals(0, count("planForm:error:refusedTick"))
        node("planWizard:next").assertIsEnabled()
        r.close()
    }

    @Test
    fun `switching back to At most once clears the tick and asks again on the next switch`() = runDesktopComposeUiTest(1280, 800) {
        val r = rigWithBroker()
        toForm(r)
        typeQueue("orders")
        node("planWizard:deadLetter:strategyAtLeastOnce").performClick()
        node("planWizard:deadLetter:refusedTick").performClick()
        node("planWizard:deadLetter:strategyAtMostOnce").performClick()
        assertEquals(false, r.form().publishersRefusedAccepted)
        assertEquals(DeadLetterStrategy.AtMostOnce, r.form().strategy)
        assertEquals(0, count("planWizard:deadLetter:refusedTick"), "the tick is only for At least once")
        node("planWizard:deadLetter:strategyAtLeastOnce").performClick()
        assertEquals(false, ticked(), "asked again")
        node("planWizard:next").assertIsNotEnabled()
        r.close()
    }

    // ---- notes ----

    @Test
    fun `a queue that already has a policy gets the sentence of the Policy form under the queue`() = runDesktopComposeUiTest(1280, 800) {
        val row = PolicyRow("limits", "/", "^invoices", "all", -1, mapOf("max-length" to ArgumentValue.Whole(5)))
        val r = rigWithBroker(policies = FakeBrokerPolicyReader().policy(row))
        toForm(r)
        typeQueue("invoices")
        waitForIdle()
        assertEquals(
            "The broker applies the policy limits to invoices. Only one policy applies to a queue: this one would take its place.",
            textOf("planWizard:deadLetter:notes"),
        )
        node("planWizard:next").assertIsEnabled()
        typeQueue("orders")
        waitForIdle()
        assertEquals(0, sentenceCount("Only one policy applies"), "the note of another queue is gone")
        r.close()
    }

    @Test
    fun `a queue that declares its own dead-letter exchange gets the note, and Next stays enabled`() = runDesktopComposeUiTest(1280, 800) {
        val own = KnownTopology(queues = setOf("orders"), queueDetails = mapOf("orders" to KnownQueue("quorum", null, ownDeadLetterExchange = "legacy-dlx")))
        val r = rigWithBroker(known = own)
        toForm(r)
        typeQueue("orders")
        assertTrue(
            sentenceCount("The queue orders names its own dead-letter exchange legacy-dlx. RabbitMQ uses that one and ignores this policy's exchange.") >= 1,
            "the own-argument note is on the page",
        )
        node("planWizard:next").assertIsEnabled()
        r.close()
    }

    // ---- summary, plan, graph ----

    @Test
    fun `a complete form goes through the summary into the plan with the four steps, and the graph shows the dead-letter link`() = runDesktopComposeUiTest(1280, 800) {
        val r = rigWithBroker()
        toForm(r)
        typeQueue("orders")
        node("planWizard:next").performClick()
        val summary = textOf("planWizard:summary")
        listOf("Declare fanout exchange orders-dlx", "Declare quorum queue orders-dlq", "Bind orders-dlx to orders-dlq", "Declare policy orders-dlx").forEach {
            assertTrue(summary.contains(it), "the summary lists \"$it\": $summary")
        }
        assertTrue(textOf("planWizard:summaryNote").contains(PlannerTexts.DEAD_LETTER_SUMMARY_CAUSES), textOf("planWizard:summaryNote"))
        assertTrue(textOf("planWizard:summaryNote").contains("The queue orders itself is not changed."), textOf("planWizard:summaryNote"))
        node("planWizard:add").performClick()
        assertEquals(
            listOf("Declare fanout exchange orders-dlx", "Declare quorum queue orders-dlq", "Bind orders-dlx to orders-dlq", "Declare policy orders-dlx"),
            r.state.plan.steps.map { it.action.description },
        )
        val policy = r.state.plan.steps.last().action as MigrationAction.DeclarePolicy
        assertEquals("quorum_queues", policy.applyTo)
        assertEquals(mapOf("dead-letter-exchange" to ArgumentValue.Text("orders-dlx")), policy.definition)
        assertEquals("dead letters", textOf("planGraphLink:q:orders:e:orders-dlx"))
        r.close()
    }

    @Test
    fun `Back from the summary keeps what was chosen`() = runDesktopComposeUiTest(1280, 800) {
        val r = rigWithBroker()
        toForm(r)
        typeQueue("orders")
        node("planWizard:deadLetter:strategyAtLeastOnce").performClick()
        node("planWizard:deadLetter:refusedTick").performClick()
        node("planWizard:next").performClick()
        node("planWizard:back").performClick()
        assertEquals(DeadLetterForm("orders", strategy = DeadLetterStrategy.AtLeastOnce, publishersRefusedAccepted = true), r.form())
        assertTrue(ticked())
        r.close()
    }
}

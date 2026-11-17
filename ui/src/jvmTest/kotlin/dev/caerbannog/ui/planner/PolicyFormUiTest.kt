package dev.caerbannog.ui.planner

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.planner.KnownQueue
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.PlannerTexts
import dev.caerbannog.core.planner.PolicyForm
import dev.caerbannog.core.planner.PolicySetting
import dev.caerbannog.core.planner.PolicyTarget
import dev.caerbannog.core.planner.WizardState
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M9-08 slice 2, written first: the Policy card and its form in the wizard (mockup "4. Policy"), 1280 x 800. RED until `PolicyFormView` exists. The tag contract is
 * in [UiRig] (the architect confirms it in the report; the brief's list is kept, the dropdown option tags and the choice value control are the open details).
 * Behaviour pinned where the mockup is silent:
 *  - the card sits after Stream and before MQTT route, with `kindDescription("Policy")` = "Limits and other settings by name pattern";
 *  - the form opens for target All, priority "0", no setting row; the "Add setting" button is always there; Next is disabled with `Add at least one setting` under the list;
 *  - the match line (`planForm:policyMatches`) is drawn while the pattern is not empty and says what `FieldHint("pattern", ...)` of the core says;
 *  - a key dropdown offers only keys of the target that no other row uses; a value that is a number refuses text under its own row (`planForm:error:setting:<i>`);
 *  - choosing another target removes the rows whose key is no longer offered and says so in the page message line (`planWizard:pageMessage`, wording is the owner's
 *    decision 2 and is NOT asserted here), and says nothing when no row is lost.
 */
@OptIn(ExperimentalTestApi::class)
class PolicyFormUiTest {

    private fun DesktopComposeUiTest.toPolicyForm(r: UiRig) {
        showPlanner(r)
        node("planner:addStepBtn").performClick()
        node("planWizard:kind:Policy").performClick()
        node("planWizard:next").performClick()
    }

    private fun DesktopComposeUiTest.fieldText(tag: String) =
        node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun UiRig.form() = assertIs<PolicyForm>(assertIs<WizardState.Details>(state.wizard).form)

    private fun DesktopComposeUiTest.chooseKey(row: Int, key: String) {
        node("planForm:setting:$row:name").performClick()
        node("planForm:settingOption:$key").performClick()
    }

    private fun DesktopComposeUiTest.chooseTarget(wire: String) {
        node("planForm:policyTarget").performClick()
        node("planForm:policyTarget:$wire").performClick()
    }

    private fun DesktopComposeUiTest.countTagged(prefix: String) =
        onAllNodes(androidx.compose.ui.test.SemanticsMatcher("tag starts with $prefix") { (it.config.getOrNull(SemanticsProperties.TestTag) ?: "").startsWith(prefix) }, useUnmergedTree = true)
            .fetchSemanticsNodes().size

    // ---- page 1 ----

    @Test
    fun `page 1 draws Policy after Stream and before MQTT route, and Next sends the kind`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        showPlanner(r)
        node("planner:addStepBtn").performClick()
        node("planWizard:kind:Policy").assertIsDisplayed()
        assertTrue(textOf("planWizard:kind:Policy").contains("Limits and other settings by name pattern"), textOf("planWizard:kind:Policy"))
        val y = { kind: String -> node("planWizard:kind:$kind").fetchSemanticsNode().positionInRoot.y }
        assertTrue(y("Stream") < y("Policy") && y("Policy") < y("MQTT route"), "Policy is drawn between Stream and MQTT route")
        node("planWizard:kind:Policy").performClick()
        node("planWizard:next").performClick()
        assertTrue(r.events.calls.contains("chooseKind:Policy"), r.events.calls.toString())
        assertEquals(PolicyForm(name = "", pattern = ""), r.form())
        r.close()
    }

    // ---- page 2: the empty state ----

    @Test
    fun `the form opens empty for All with priority 0, no setting row, and Next disabled with the reason under the list`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toPolicyForm(r)
        listOf("planForm:name", "planForm:pattern", "planForm:policyTarget", "planForm:priority", "planForm:addSetting").forEach { node(it).assertIsDisplayed() }
        assertEquals("0", fieldText("planForm:priority"))
        assertTrue(textOf("planForm:policyTarget").contains("All"), textOf("planForm:policyTarget"))
        assertEquals(0, countTagged("planForm:setting:"), "no setting row yet")
        node("planForm:addSetting").assertIsEnabled()
        assertEquals("Add at least one setting", textOf("planForm:error:settings"))
        assertEquals("Give the policy a name", textOf("planForm:error:name"))
        assertEquals("Enter a pattern", textOf("planForm:error:pattern"))
        node("planWizard:next").assertIsNotEnabled()
        assertEquals("Only settings that every kind of queue honours are offered here", textOf("planForm:policyShortList"))
        r.close()
    }

    @Test
    fun `typing sends the form with the typed name, pattern and priority, and the amq prefix is not refused`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toPolicyForm(r)
        node("planForm:name").performTextInput("amq.orders-limits")
        node("planForm:pattern").performTextInput("^orders")
        node("planForm:priority").performTextReplacement("-5")
        assertEquals(PolicyForm(name = "amq.orders-limits", pattern = "^orders", priority = "-5"), r.form())
        assertTrue(onAllNodesCount("planForm:error:name") == 0, "a policy named amq.x is accepted by the broker")
        assertTrue(onAllNodesCount("planForm:error:priority") == 0)
        node("planForm:priority").performTextReplacement("high")
        assertEquals("Enter a whole number", textOf("planForm:error:priority"))
        r.close()
    }

    private fun DesktopComposeUiTest.onAllNodesCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.SemanticsMatcher("tag is $tag") { it.config.getOrNull(SemanticsProperties.TestTag) == tag }, useUnmergedTree = true).fetchSemanticsNodes().size

    // ---- the match line ----

    @Test
    fun `the match line names the plan's names and, when the broker was read, the broker's`() = runDesktopComposeUiTest(1280, 800) {
        val broker = KnownTopology(queues = setOf("orders-archive"), queueDetails = mapOf("orders-archive" to KnownQueue("quorum", null)))
        val r = UiRig(reader = FakeKnownTopologyReader(broker)) { queue("orders"); queue("invoices") }
        toPolicyForm(r)
        assertEquals(0, onAllNodesCount("planForm:policyMatches"), "no line while the pattern is empty")
        node("planForm:pattern").performTextInput("^orders")
        assertEquals("""matches "orders" in this plan and "orders-archive" on "uat-eu"""", textOf("planForm:policyMatches"))
        node("planForm:pattern").performTextReplacement("^zzz")
        assertEquals("Matches no name yet", textOf("planForm:policyMatches"))
        r.close()
    }

    @Test
    fun `without a broker read the broker part is dropped`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig(reader = null) { queue("orders") }
        toPolicyForm(r)
        node("planForm:pattern").performTextInput("^orders")
        assertEquals("""matches "orders" in this plan""", textOf("planForm:policyMatches"))
        r.close()
    }

    @Test
    fun `a pattern the app cannot evaluate says so and does not block Next`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toPolicyForm(r)
        node("planForm:name").performTextInput("p")
        node("planForm:pattern").performTextInput("(?=orders)")
        assertEquals("The app cannot tell which names this pattern matches", textOf("planForm:policyMatches"))
        assertEquals(0, onAllNodesCount("planForm:error:pattern"), "it is a hint, not an error")
        r.vm.updateForm(r.form().copy(settings = listOf(PolicySetting("max-length-bytes", "1000"))))
        node("planWizard:next").assertIsEnabled()
        r.close()
    }

    // ---- settings rows ----

    @Test
    fun `Add setting adds a row, the key dropdown offers only the keys of the target, and a chosen key is not offered twice`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toPolicyForm(r)
        node("planForm:addSetting").performClick()
        node("planForm:setting:0:name").assertIsDisplayed()
        node("planForm:setting:0:name").performClick()
        listOf("max-length-bytes", "queue-leader-locator", "alternate-exchange").forEach { node("planForm:settingOption:$it").assertIsDisplayed() }
        listOf("max-length", "delivery-limit", "max-age", "dead-letter-exchange").forEach { assertEquals(0, onAllNodesCount("planForm:settingOption:$it"), "$it is offered for All") }
        node("planForm:settingOption:max-length-bytes").performClick()
        assertEquals("max-length-bytes", r.form().settings.single().key)
        assertTrue(textOf("planForm:setting:0:name").contains("Keep at most (bytes)"), textOf("planForm:setting:0:name"))
        node("planForm:addSetting").performClick()
        node("planForm:setting:1:name").performClick()
        assertEquals(0, onAllNodesCount("planForm:settingOption:max-length-bytes"), "a key already used by another row is not offered")
        node("planForm:settingOption:alternate-exchange").assertIsDisplayed()
        r.close()
    }

    @Test
    fun `a number refuses text under its own row and takes digits`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toPolicyForm(r)
        chooseTarget("quorum_queues")
        node("planForm:addSetting").performClick()
        chooseKey(0, "max-length")
        node("planForm:setting:0:value").performTextInput("many")
        assertEquals("Enter a whole number", textOf("planForm:error:setting:0"))
        node("planForm:setting:0:value").performTextReplacement("1000")
        assertEquals(0, onAllNodesCount("planForm:error:setting:0"))
        assertEquals(PolicySetting("max-length", "1000"), r.form().settings.single())
        r.close()
    }

    @Test
    fun `a duration refuses a lowercase d under its row`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toPolicyForm(r)
        chooseTarget("streams")
        node("planForm:addSetting").performClick()
        chooseKey(0, "max-age")
        node("planForm:setting:0:value").performTextInput("7d")
        assertEquals("Enter a number and a unit, for example 7D", textOf("planForm:error:setting:0"))
        node("planForm:setting:0:value").performTextReplacement("7D")
        assertEquals(0, onAllNodesCount("planForm:error:setting:0"))
        r.close()
    }

    @Test
    fun `the remove button takes the row away and the list is empty again`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toPolicyForm(r)
        node("planForm:addSetting").performClick()
        chooseKey(0, "max-length-bytes")
        node("planForm:setting:0:remove").performClick()
        assertEquals(emptyList(), r.form().settings)
        assertEquals(0, countTagged("planForm:setting:"))
        assertEquals("Add at least one setting", textOf("planForm:error:settings"))
        r.close()
    }

    // ---- the target ----

    @Test
    fun `choosing a target sends it, the sentence for All and Queues goes away for the others`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toPolicyForm(r)
        chooseTarget("streams")
        assertEquals(PolicyTarget.STREAMS, r.form().target)
        assertEquals(0, onAllNodesCount("planForm:policyShortList"))
        assertTrue(textOf("planForm:policyTarget").contains("Streams"))
        chooseTarget("queues")
        assertEquals("Only settings that every kind of queue honours are offered here", textOf("planForm:policyShortList"))
        chooseTarget("exchanges")
        node("planForm:addSetting").performClick()
        node("planForm:setting:0:name").performClick()
        node("planForm:settingOption:alternate-exchange").assertIsDisplayed()
        assertEquals(0, onAllNodesCount("planForm:settingOption:max-length-bytes"))
        r.close()
    }

    @Test
    fun `a stream policy offers no dead-letter setting`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toPolicyForm(r)
        chooseTarget("streams")
        node("planForm:addSetting").performClick()
        node("planForm:setting:0:name").performClick()
        listOf("max-age", "max-length-bytes", "stream-max-segment-size-bytes", "queue-leader-locator", "alternate-exchange").forEach { node("planForm:settingOption:$it").assertIsDisplayed() }
        listOf("dead-letter-exchange", "dead-letter-routing-key", "dead-letter-strategy").forEach { assertEquals(0, onAllNodesCount("planForm:settingOption:$it")) }
        r.close()
    }

    @Test
    fun `choosing another target removes the rows that are no longer offered and says so, and says nothing when no row is lost`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toPolicyForm(r)
        chooseTarget("quorum_queues")
        node("planForm:addSetting").performClick()
        chooseKey(0, "delivery-limit")
        node("planForm:setting:0:value").performTextInput("5")
        node("planForm:addSetting").performClick()
        chooseKey(1, "max-length-bytes")
        node("planForm:setting:1:value").performTextInput("9")
        assertEquals(listOf(PolicySetting("delivery-limit", "5"), PolicySetting("max-length-bytes", "9")), r.form().settings)
        assertEquals(0, onAllNodesCount("planWizard:pageMessage"), "nothing was lost yet")
        chooseTarget("streams")
        assertEquals(listOf(PolicySetting("max-length-bytes", "9")), r.form().settings, "delivery-limit is not a stream setting: the row is removed, not hidden")
        assertEquals(1, onAllNodesCount("planForm:setting:0:name") + onAllNodesCount("planForm:setting:1:name").let { if (it > 0) 100 else 0 }, "one row is left")
        assertTrue(textOf("planWizard:pageMessage").isNotBlank(), "the page says that a row was removed")
        chooseTarget("all")
        assertEquals(listOf(PolicySetting("max-length-bytes", "9")), r.form().settings, "nothing more was lost")
        assertEquals(0, onAllNodesCount("planWizard:pageMessage"), "and nothing is said")
        r.close()
    }

    // ---- Next, summary, the plan ----

    @Test
    fun `a complete form goes through the summary into the plan, with the typed definition and the detail of the step`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toPolicyForm(r)
        node("planForm:name").performTextInput("orders-limits")
        node("planForm:pattern").performTextInput("^orders")
        chooseTarget("quorum_queues")
        node("planForm:priority").performTextReplacement("1")
        node("planForm:addSetting").performClick()
        chooseKey(0, "max-length")
        node("planForm:setting:0:value").performTextInput("1000")
        node("planWizard:next").assertIsEnabled().performClick()
        assertTrue(textOf("planWizard:summary").contains("Declare policy orders-limits"), textOf("planWizard:summary"))
        node("planWizard:add").performClick()
        assertEquals(
            listOf(MigrationAction.DeclarePolicy("orders-limits", "^orders", mapOf("max-length" to ArgumentValue.Whole(1000)), "/", "quorum_queues", 1)),
            r.state.plan.steps.map { it.action },
        )
        assertTrue(textOf("planStep:0").contains("apply-to=quorum_queues, priority=1; max-length=1000"), textOf("planStep:0"))
        r.close()
    }

    @Test
    fun `Enter in the name field does not leave an incomplete form`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig()
        toPolicyForm(r)
        node("planForm:name").requestFocus()
        node("planForm:name").performKeyInput { pressKey(Key.Enter) }
        assertIs<WizardState.Details>(r.state.wizard)
        r.close()
    }

    // ---- notes in the step list ----

    private val overlap = "The policies orders-dlx and orders-limits both match orders. RabbitMQ applies only one policy to a queue: put the settings into one policy."

    @Test
    fun `two policies of the plan that match one queue each show the note, Save stays enabled`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig {
            queue("orders")
            add(PolicyForm("orders-dlx", "^orders$", PolicyTarget.QUORUM_QUEUES, "0", listOf(PolicySetting("max-length", "10"))))
            add(PolicyForm("orders-limits", "^orders", PolicyTarget.QUORUM_QUEUES, "0", listOf(PolicySetting("message-ttl", "5"))))
        }
        showPlanner(r)
        assertEquals(overlap, textOf("planStepNote:1"))
        assertEquals(overlap, textOf("planStepNote:2"))
        assertEquals(0, onAllNodesCount("planStepNote:0"))
        node("planner:saveBtn").assertIsEnabled()
        r.close()
    }

    @Test
    fun `a policy a quorum queue only half honours is said to have no effect`() = runDesktopComposeUiTest(1280, 800) {
        val r = UiRig {
            store.putDraft("", listOf(MigrationAction.DeclareQueue("orders"), MigrationAction.DeclarePolicy("orders-old", "^orders$", mapOf("max-length" to ArgumentValue.Whole(10), "max-age" to ArgumentValue.Text("7D")))))
        }
        showPlanner(r)
        assertEquals(
            "This policy has no effect on orders: a quorum queue ignores Keep messages for, and RabbitMQ then applies none of the policy's settings.",
            textOf("planStepNote:1"),
        )
        r.close()
    }
}

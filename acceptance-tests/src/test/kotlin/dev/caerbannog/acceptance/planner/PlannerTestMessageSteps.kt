package dev.caerbannog.acceptance.planner

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import dev.caerbannog.acceptance.migrations.ScreenDriver
import dev.caerbannog.core.connections.fakes.FakeMqttMessagePublisher
import dev.caerbannog.core.migrations.ActionDryRun
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationDryRun
import dev.caerbannog.core.migrations.MigrationEntry
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationStatus
import dev.caerbannog.core.migrations.MigrationsScreenState
import dev.caerbannog.core.mqtt.CorrelationIdSource
import dev.caerbannog.core.mqtt.MqttQos
import dev.caerbannog.core.mqtt.PublishMqttViewModel
import dev.caerbannog.core.planner.MqttRoutes
import dev.caerbannog.core.planner.MqttTopicTranslation
import dev.caerbannog.core.planner.Translation
import dev.caerbannog.ui.migrations.MigrationsScreen
import dev.caerbannog.ui.mqtt.PublishMqttDialog
import dev.caerbannog.ui.mqtt.PublishMqttEvents
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The state behind one scenario of `@m9-10b`: the real Migrations screen over a hand-built [MigrationsScreenState] (no view model: the scenarios are about
 * what the screen offers for an applied file), and, when the screen reports an opening, the real Publish MQTT dialog over the real [PublishMqttViewModel]
 * and the [FakeMqttMessagePublisher]. This is what the window does in `desktopApp`; the glue builds the view model from the opening the same way.
 * No broker: the fake publisher records, nothing is sent.
 */
@OptIn(ExperimentalTestApi::class)
internal class TestMessageHost {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val publisher = FakeMqttMessagePublisher()
    private var counter = 0
    val ids = CorrelationIdSource { "generated-${++counter}" }

    var hasMqtt = true
    var publishing = true
    var files: List<MigrationEntry> = emptyList()

    /** The Compose state the screen reads; replaced whole by [refresh]. */
    var state by mutableStateOf(MigrationsScreenState(profileName = "uat-eu"))
    var vm by mutableStateOf<PublishMqttViewModel?>(null)

    fun refresh(selected: String? = state.selectedVersion) {
        val entry = files.firstOrNull { it.file.version == selected }
        state = MigrationsScreenState(
            entries = files, selectedVersion = selected, selectedFile = entry?.file, profileName = "uat-eu", vhost = "/",
            dryRun = entry?.let { e -> MigrationDryRun(e.file.version, e.file.up.map { ActionDryRun(it, allowed = true) }, canApply = false) },
            hasMqtt = hasMqtt, publishingEnabled = publishing,
        )
    }
}

@OptIn(ExperimentalTestApi::class)
internal class TestMessageDriver(private val host: TestMessageHost) : ScreenDriver({
    Box {
        MigrationsScreen(
            state = host.state,
            onSelectVersion = { host.refresh(it) },
            onApplyPending = {}, onApplySingle = {}, onImportJson = {}, onExportTopology = {}, onUpdateFile = { _, _ -> }, onValidateAgain = {}, onDismissError = {},
            onSendTestMessage = { opening ->
                host.vm = PublishMqttViewModel(
                    publisher = host.publisher, connectionName = "uat-eu", scope = host.scope, initialTopic = opening.topic,
                    initialHeaders = opening.headers, headerNotice = opening.notice,
                )
            },
            correlationIds = host.ids,
        )
        host.vm?.let { vm ->
            val dialogState by vm.state.collectAsState()
            PublishMqttDialog(
                state = dialogState,
                events = object : PublishMqttEvents {
                    override fun setTopic(topic: String) = vm.setTopic(topic)
                    override fun setQos(qos: MqttQos) = vm.setQos(qos)
                    override fun setRetain(retain: Boolean) = vm.setRetain(retain)
                    override fun setPayload(payload: String) = vm.setPayload(payload)
                    override fun addHeader() = vm.addHeader()
                    override fun setHeader(index: Int, key: String, value: String) = vm.setHeader(index, key, value)
                    override fun removeHeader(index: Int) = vm.removeHeader(index)
                    override fun publish() = vm.publish()
                    override fun cancel() { vm.cancel(); host.vm = null }
                    override fun clearFeedback() = vm.clearFeedback()
                },
            )
        }
    }
}) {
    fun dismissPublishDialog() = exec { host.vm = null; waitForIdle() }

    fun replaceText(tag: String, text: String) = exec { waitForIdle(); onNodeWithTag(tag).performTextReplacement(text); waitForIdle() }

    /** What is typed in the field with [tag]. */
    fun editableText(tag: String): String = exec {
        waitForIdle()
        onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
    }

    fun liveRegion(tag: String): LiveRegionMode? = exec {
        waitForIdle()
        onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)
    }

    fun tagsWithPrefix(prefix: String): List<String> = exec {
        waitForIdle()
        onAllNodes(SemanticsMatcher("tag starts with $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }, useUnmergedTree = true)
            .fetchSemanticsNodes().mapNotNull { n: SemanticsNode -> n.config.getOrNull(SemanticsProperties.TestTag) }.distinct()
    }
}

/** Steps of the `@m9-10b` scenarios of `21_topology_planner_recipes.feature`: "Send a test message" on the Migrations screen and the dialog it opens. */
@OptIn(ExperimentalTestApi::class)
class PlannerTestMessageSteps {
    private var host = TestMessageHost()
    private var driverOrNull: TestMessageDriver? = null
    private val driver: TestMessageDriver get() = driverOrNull ?: TestMessageDriver(host).also { driverOrNull = it }
    private var routeRow = 0
    private var firstGenerated: String? = null

    @Before(value = "@m9-10b", order = 20000)
    fun fresh() {
        current = this
        host = TestMessageHost()
        driverOrNull = null
        routeRow = 0
        firstGenerated = null
    }

    @After(value = "@m9-10b")
    fun end() {
        driverOrNull?.dispose()
        driverOrNull = null
        host.scope.cancel()
        if (current === this) current = null
    }

    // ---- the connection ----

    @Given("I am connected to {string} with MQTT port {int} and the user may publish")
    fun connected(name: String, port: Int) {
        assertEquals("uat-eu", name, "the screen state of this glue is for the profile uat-eu")
        host.hasMqtt = port > 0
        host.publishing = true
    }

    @Given("the connection has no MQTT port")
    fun noPort() { host.hasMqtt = false }

    @Given("the user has no write permission")
    fun noWrite() { host.publishing = false }

    // ---- the migration ----

    private fun key(filter: String) = (MqttTopicTranslation.toBindingKey(filter) as Translation.Key).text

    private fun put(version: String, actions: List<MigrationAction>, status: MigrationStatus) {
        val file = MigrationFile(version, "Routes", actions, """{ "version": "$version" }""", "sum$version")
        host.files = host.files.filter { it.file.version != version } + MigrationEntry(file, status)
        routeRow = actions.indexOfFirst { MqttRoutes.routeOf(it) != null }
        check(routeRow >= 0) { "no MQTT route among $actions" }
        host.refresh(version)
    }

    private fun declare(word: String, name: String): MigrationAction = when (word) {
        "stream" -> MigrationAction.DeclareStream(name)
        "queue" -> MigrationAction.DeclareQueue(name, "quorum")
        else -> error("the destination is a stream or a queue, not \"$word\"")
    }

    @Given("the migration {string} with the MQTT route {string} to the {word} {string} is applied to {string}")
    fun applied(version: String, filter: String, word: String, destination: String, profile: String) {
        assertEquals("uat-eu", profile)
        put(version, listOf(declare(word, destination), MigrationAction.DeclareBinding("amq.topic", destination, key(filter))), MigrationStatus.Applied("sum$version"))
    }

    @Given("the migration {string} with only the MQTT route {string} to {string} is applied to {string}")
    fun appliedOnly(version: String, filter: String, destination: String, profile: String) {
        assertEquals("uat-eu", profile)
        put(version, listOf(MigrationAction.DeclareBinding("amq.topic", destination, key(filter))), MigrationStatus.Applied("sum$version"))
    }

    @Given("the pending migration {string} with the MQTT route {string} to the stream {string}")
    fun pending(version: String, filter: String, destination: String) =
        put(version, listOf(declare("stream", destination), MigrationAction.DeclareBinding("amq.topic", destination, key(filter))), MigrationStatus.Pending)

    @Given("the test message of the route {string} asks for the value of the {string}")
    fun askedFor(filter: String, wildcard: String) {
        assertEquals("+", wildcard)
        put("0005", listOf(declare("stream", "telemetry"), MigrationAction.DeclareBinding("amq.topic", "telemetry", key(filter))), MigrationStatus.Applied("sum0005"))
        chooseButton()
        assertTrue(driver.isDrawn("testMessageDialog"), "the value dialog is not drawn")
    }

    @When("I select {string} on the Migrations screen")
    fun select(version: String) {
        // M9-12: one sentence, two screens: when a Given of M9-12 put a real file in the folder, the REAL view model's Migrations screen is the one to click
        if (PlannerFiles.active) return PlannerFiles.select(version)
        driver.click("migrationCard:$version")
        assertTrue(driver.isDrawn("dryRunAction:$routeRow"), "the actions of $version are not drawn")
    }

    // ---- choosing the button ----

    private fun chooseButton() {
        // The window closes the dialog; the glue does it directly, so this step does not depend on where the Cancel button is on screen.
        if (driver.isDrawn("publishMqttDialog")) driver.dismissPublishDialog()
        driver.click("migrations:testMessageBtn:$routeRow")
    }

    @When("I choose {string} for that route on the Migrations screen")
    fun choose(button: String) {
        assertEquals("Send a test message", button)
        chooseButton()
    }

    @When("I choose {string} for that route and enter {string}")
    fun chooseAndEnter(button: String, value: String) { choose(button); enter(value) }

    @When("I open the test message again and enter {string}")
    fun again(value: String) { firstGenerated = headerValue(); chooseButton(); enter(value) }

    @When("I enter {string}")
    fun enter(value: String) {
        assertTrue(driver.isDrawn("testMessageDialog"), "no value is asked for")
        driver.replaceText("testMessageValue:0", value)
        driver.click("testMessageOpenBtn")
    }

    // ---- the value dialog ----

    @Then("I am asked for a value for the {string} in level {int}, which starts as {string}")
    fun askedPlus(wildcard: String, level: Int, start: String) {
        assertEquals("+", wildcard)
        assertTrue(driver.isDrawn("testMessageDialog"), "no value dialog")
        assertTrue(driver.textOf("testMessageValue:0").orEmpty().contains("Value for the + in level $level"), "label: ${driver.textOf("testMessageValue:0")}")
        assertEquals(start, driver.editableText("testMessageValue:0"))
        assertFalse(driver.isDrawn("testMessageValue:1"), "one field per wildcard")
    }

    @Then("I am asked for a value for the rest of the topic, which starts as {string}")
    fun askedHash(start: String) {
        assertTrue(driver.isDrawn("testMessageDialog"), "no value dialog")
        assertTrue(driver.textOf("testMessageValue:0").orEmpty().contains("Value for the rest of the topic"), "label: ${driver.textOf("testMessageValue:0")}")
        assertEquals(start, driver.editableText("testMessageValue:0"))
    }

    @Then("no value is asked for")
    fun noValue() = assertFalse(driver.isDrawn("testMessageDialog"), "a value dialog is drawn")

    companion object {
        private var current: PlannerTestMessageSteps? = null

        /** True while the value dialog of a test message is drawn: then "it says" (PlannerGraphSteps) is the message under its field. */
        val valueDialogOpen: Boolean get() = current?.driverOrNull?.isDrawn("testMessageDialog") == true

        fun disabledAndSaysOnCurrent(button: String, reason: String) =
            checkNotNull(current) { "no test message scenario is running" }.disabledAndSays(button, reason)

        fun saysUnderField(message: String) {
            val steps = checkNotNull(current) { "no test message scenario is running" }
            assertEquals(message, steps.driver.textOf("testMessageError:0"), "the message under the value field")
        }
    }

    @Then("the dialog is not opened")
    fun notOpened() {
        assertFalse(driver.isDrawn("publishMqttDialog"), "the Publish MQTT dialog is open")
        assertTrue(host.publisher.publishedMessages.isEmpty())
    }

    // ---- the Publish MQTT dialog ----

    @Then("the Publish MQTT dialog opens with the topic {string}")
    fun opensWith(topic: String) {
        assertTrue(driver.isDrawn("publishMqttDialog"), "the Publish MQTT dialog is not open")
        assertEquals(topic, driver.editableText("publishMqttTopicInput"))
    }

    @Then("nothing is published until I press Publish")
    fun nothingPublished() = assertEquals(emptyList(), host.publisher.publishedMessages.toList(), "a message was published")

    @Then("nothing was published")
    fun nothingWasPublished() = nothingPublished()

    private fun headerValue(): String = driver.editableText("publishMqttHeaderValue:0")

    @Then("the dialog holds one header {string} with a generated value")
    fun oneHeader(key: String) {
        assertEquals(key, driver.editableText("publishMqttHeaderKey:0"))
        assertFalse(driver.isDrawn("publishMqttHeaderKey:1"), "more than one header is drawn")
        assertTrue(headerValue().isNotBlank(), "the value is empty")
        assertEquals(listOf(key), host.vm!!.state.value.headers.map { it.key })
    }

    @Then("it is an MQTT 5 user property like every header of the dialog")
    fun userProperty() {
        assertTrue(driver.textOf("publishMqttDialog").orEmpty().contains("Sent as MQTT 5 user properties"), "the dialog's own line about headers")
        // The only header list of the dialog is the one the request is built from; a second kind of field would show here.
        assertEquals(1, host.vm!!.state.value.headers.size)
    }

    @Then("the generated value is a different one")
    fun different() = assertNotEquals(firstGenerated, headerValue(), "the generated value is the same as the first opening")

    @Then("under the header {string} a warning says {string}")
    fun warningSays(key: String, text: String) {
        assertEquals(key, driver.editableText("publishMqttHeaderKey:0"))
        assertEquals(text, driver.textOf("publishMqttHeaderNotice"), "the warning under the headers")
    }

    @Then("the warning is shown as a warning and not as a hint")
    fun asWarning() {
        assertEquals(LiveRegionMode.Polite, driver.liveRegion("publishMqttHeaderNotice"), "the warning is a polite live region")
        assertNotEquals("publishMqttHeaderNotice", "publishMqttWarningBanner")
        assertTrue(driver.isDrawn("publishMqttWarningBanner"), "the broker-name banner is another element and stays")
    }

    @Then("the dialog has no field for MQTT 5 correlation data")
    fun noCorrelationData() {
        val tags = driver.tagsWithPrefix("publishMqtt")
        val suspicious = tags.filter { it != "publishMqttHeaderNotice" && (it.contains("orrelation", true) || it.contains("esponse", true)) }
        assertEquals(emptyList(), suspicious, "fields of the dialog: $tags")
        val text = driver.textOf("publishMqttDialog").orEmpty().lowercase()
        assertFalse(text.contains("correlation data") || text.contains("response topic"), "the dialog says: $text")
    }

    @Then("no warning is shown under the headers")
    fun noWarning() = assertFalse(driver.isDrawn("publishMqttHeaderNotice"), "a warning is shown")

    @Then("a warning is shown under the headers")
    fun warning() = assertTrue(driver.isDrawn("publishMqttHeaderNotice"), "no warning is shown")

    @Then("Publish is enabled")
    fun publishEnabled() = assertTrue(driver.isEnabled("publishMqttPublishButton"), "Publish is disabled")

    @When("I rename the header {string} to {string}")
    fun rename(old: String, new: String) {
        val index = (0..9).firstOrNull { driver.isDrawn("publishMqttHeaderKey:$it") && driver.editableText("publishMqttHeaderKey:$it") == old }
            ?: error("no header named \"$old\"")
        driver.replaceText("publishMqttHeaderKey:$index", new)
    }

    // ---- the button's states ----

    /** Called from `PlannerSteps.saveDisabled`, which owns the one `{string} is disabled and says {string}` step and dispatches on the button text. */
    fun disabledAndSays(button: String, reason: String) {
        assertEquals("Send a test message", button)
        assertTrue(driver.isDrawn("migrations:testMessageBtn:$routeRow"), "the button is not drawn")
        assertFalse(driver.isEnabled("migrations:testMessageBtn:$routeRow"), "the button is enabled")
        assertEquals(reason, driver.textOf("migrations:testMessageReason:$routeRow"), "the reason")
    }

    @Then("{string} is not offered")
    fun notOffered(button: String) {
        // M9-09: "At least once" is not offered by the dead-lettering form (one sentence, two screens)
        if (button == "At least once") return PlannerDeadLetterSteps().atLeastOnceNotOffered()
        // M9-12: the buttons of the action row of a file on the real Migrations screen
        if (button == "Copy into a new plan" || button == "Edit steps") return PlannerEditFileSteps().notDrawn(button)
        assertEquals("Send a test message", button)
        for (i in 0..5) {
            assertFalse(driver.isDrawn("migrations:testMessageBtn:$i"), "a button is drawn on row $i")
            assertFalse(driver.isDrawn("migrations:testMessageReason:$i"), "a reason is drawn on row $i")
        }
    }
}

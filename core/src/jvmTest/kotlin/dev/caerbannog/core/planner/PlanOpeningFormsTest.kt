package dev.caerbannog.core.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.topology.BindingDestinationType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * M9-12 step 2: the opening rules per kind, proved THROUGH A FILE (the rules already live in `StepForms.formOf`; only the path is new): the actions are written
 * as a file, read by the parser, turned into steps with `PlanOpening.planOf` and asked for a form. RED by compilation until `PlanOpening.planOf` exists.
 * Recipes leave no mark (brief, step 1 record): the four steps of a dead-lettering recipe open as four ordinary steps.
 */
class PlanOpeningFormsTest {
    private fun formsOf(vararg actions: MigrationAction): List<StepForm?> {
        val raw = MigrationParser.exportToJson("0005", "d", actions.toList())
        val file: MigrationFile = (MigrationParser.parse(raw) as MigrationValidationResult.Valid).migration
        return PlanOpening.planOf(file, "/").steps.map { StepForms.formOf(it.action) }
    }

    private fun formOf(action: MigrationAction): StepForm? = formsOf(action).single()

    private fun policy(applyTo: String, vararg entries: Pair<String, ArgumentValue>) =
        MigrationAction.DeclarePolicy("p", "^q$", entries.toMap(), "/", applyTo, 0)

    @Test
    fun `a stream with no arguments opens as the stream form with no pair tick and no retention`() {
        val form = assertIs<StreamForm>(formOf(MigrationAction.DeclareStream("audit")))
        assertEquals(StreamForm(name = "audit"), form)
    }

    @Test
    fun `a stream with arguments is read-only`() =
        assertNull(formOf(MigrationAction.DeclareStream("audit", arguments = mapOf("x-max-age" to ArgumentValue.Text("7D")))))

    @Test
    fun `a binding on amq topic that is a plain MQTT route opens as the MQTT route form`() {
        val form = assertIs<MqttRouteForm>(formOf(MigrationAction.DeclareBinding("amq.topic", "telemetry", "sensors.*.temp")))
        assertEquals("telemetry", form.destination)
    }

    @Test
    fun `a binding on amq topic that is not a plain route opens as an ordinary binding`() {
        listOf("sensors..temp", "sensors.a*.temp", "sensors.#.temp").forEach { key ->
            assertIs<BindingForm>(formOf(MigrationAction.DeclareBinding("amq.topic", "telemetry", key)), key)
        }
    }

    @Test
    fun `a binding with no arguments opens as the binding form, to a queue or to an exchange`() {
        assertIs<BindingForm>(formOf(MigrationAction.DeclareBinding("events", "orders", "k")))
        val toExchange = assertIs<BindingForm>(formOf(MigrationAction.DeclareBinding("events", "other", "k", destinationType = BindingDestinationType.EXCHANGE)))
        assertEquals(BindingDestinationType.EXCHANGE, toExchange.destinationKind)
    }

    @Test
    fun `a binding with a headers match opens as the headers form where the form accepts it, else read-only`() {
        val ok = MigrationAction.DeclareBinding("docs", "reports", arguments = mapOf("x-match" to ArgumentValue.Text("all"), "format" to ArgumentValue.Text("pdf")))
        assertIs<BindingForm>(formOf(ok))
        val held = ok.copy(arguments = mapOf("x-match" to ArgumentValue.Text("all-with-x"), "format" to ArgumentValue.Text("pdf")))
        assertEquals("all-with-x", assertIs<BindingForm>(formOf(held)).heldRule)
        assertNull(formOf(ok.copy(arguments = mapOf("x-match" to ArgumentValue.Text("all")))), "x-match alone")
        assertNull(formOf(ok.copy(arguments = mapOf("format" to ArgumentValue.Text("pdf")))), "no x-match")
        assertNull(formOf(ok.copy(arguments = mapOf("x-match" to ArgumentValue.Text("all"), "t" to ArgumentValue.Items(listOf(ArgumentValue.Text("a")))))), "a header the form cannot hold")
    }

    @Test
    fun `a policy opens as the policy form only for a target of the six with catalog keys of the right type`() {
        assertIs<PolicyForm>(formOf(policy("queues", "max-length-bytes" to ArgumentValue.Whole(10))))
        assertIs<PolicyForm>(formOf(policy("quorum_queues", "max-length" to ArgumentValue.Whole(10), "overflow" to ArgumentValue.Text("reject-publish"))))
        assertIs<PolicyForm>(formOf(policy("streams", "max-age" to ArgumentValue.Text("7D"))))
        assertIs<PolicyForm>(formOf(policy("exchanges", "alternate-exchange" to ArgumentValue.Text("alt"))))
        assertNull(formOf(policy("queues", "x-made-up" to ArgumentValue.Text("1"))), "a key the catalog does not offer")
        assertNull(formOf(policy("streams", "max-length" to ArgumentValue.Whole(10))), "a key the catalog does not offer for this target")
        assertNull(formOf(policy("queues", "max-length-bytes" to ArgumentValue.Text("ten"))), "a value of the wrong type")
        assertNull(formOf(policy("quorum_queues", "overflow" to ArgumentValue.Text("drop-everything"))), "a choice that is not one of the offered wires")
        assertNull(formOf(policy("queues", "max-length" to ArgumentValue.Whole(10))), "max-length is not offered for the generic Queues target")
    }

    @Test
    fun `an operator policy, a policy delete and every delete are read-only`() {
        listOf<MigrationAction>(
            MigrationAction.DeclareOperatorPolicy("op", "^q$", mapOf("max-length" to ArgumentValue.Whole(10)), "/", "queues", 0),
            MigrationAction.DeletePolicy("p"), MigrationAction.DeleteQueue("q"), MigrationAction.DeleteExchange("e"),
            MigrationAction.DeleteBinding("e", "q", "k"), MigrationAction.DeleteStream("s"),
        ).forEach { assertNull(formOf(it), it.description) }
    }

    @Test
    fun `a queue or an exchange with arguments or non-default flags is read-only`() {
        listOf<MigrationAction>(
            MigrationAction.DeclareQueue("q", arguments = mapOf("x-max-length" to ArgumentValue.Whole(1))),
            MigrationAction.DeclareQueue("q", durable = false), MigrationAction.DeclareQueue("q", autoDelete = true),
            MigrationAction.DeclareExchange("e", internal = true), MigrationAction.DeclareExchange("e", "x-delayed-message"),
        ).forEach { assertNull(formOf(it), it.description) }
    }

    @Test
    fun `the four steps of a dead-lettering recipe open as four ordinary steps`() {
        val recipe = listOf(
            MigrationAction.DeclareExchange("orders-dlx", "fanout"),
            MigrationAction.DeclareQueue("orders-dlq", "quorum"),
            MigrationAction.DeclareBinding("orders-dlx", "orders-dlq"),
            MigrationAction.DeclarePolicy(
                "orders-dlx", "^orders$",
                mapOf("dead-letter-exchange" to ArgumentValue.Text("orders-dlx"), "dead-letter-strategy" to ArgumentValue.Text("at-least-once"), "overflow" to ArgumentValue.Text("reject-publish")),
                "/", "quorum_queues", 0,
            ),
        )
        val forms = formsOf(*recipe.toTypedArray())
        assertEquals(listOf(ExchangeForm::class, QueueForm::class, BindingForm::class, PolicyForm::class), forms.map { it!!::class })
        assertEquals(4, forms.size)
    }

    @Test
    fun `a stream retention policy opens as a plain policy form, not folded into a stream form`() {
        val forms = formsOf(
            MigrationAction.DeclareStream("audit"),
            MigrationAction.DeclarePolicy("audit-retention", "^audit$", mapOf("max-age" to ArgumentValue.Text("7D")), "/", "streams", 0),
        )
        assertEquals(listOf(StreamForm::class, PolicyForm::class), forms.map { it!!::class })
        assertEquals(StreamForm(name = "audit"), forms[0])
    }
}

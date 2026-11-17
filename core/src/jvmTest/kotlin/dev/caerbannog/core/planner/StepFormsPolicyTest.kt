package dev.caerbannog.core.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * M9-08 slice 2, written first: `StepForms.formOf` for a policy step (A2): a `DeclarePolicy` whose `applyTo` is one of the six wires AND whose keys are all in
 * the catalog for that target AND whose values have the type the catalog says opens in `PolicyForm`; anything else is a read-only step. `DeclareOperatorPolicy` and
 * `DeletePolicy` stay read-only. The form it gives holds the values as the text of the typed value (a number as its digits), so a saved policy comes back as written.
 */
class StepFormsPolicyTest {
    private fun policy(
        applyTo: String = "quorum_queues", priority: Int = 0, definition: Map<String, ArgumentValue> = mapOf("max-length" to ArgumentValue.Whole(1000)), pattern: String = "^orders",
    ) = MigrationAction.DeclarePolicy("orders-limits", pattern, definition, "uat", applyTo, priority)

    @Test
    fun `a policy of the catalog opens in the policy form with its values as typed text`() {
        val form = StepForms.formOf(policy(priority = 3, definition = mapOf("max-length" to ArgumentValue.Whole(1000), "overflow" to ArgumentValue.Text("reject-publish"))))
        assertEquals(
            PolicyForm(
                name = "orders-limits", pattern = "^orders", target = PolicyTarget.QUORUM_QUEUES, priority = "3",
                settings = listOf(PolicySetting("max-length", "1000"), PolicySetting("overflow", "reject-publish")),
            ),
            form,
        )
    }

    @Test
    fun `a negative priority and a retention policy open too, and the round trip gives the same step back`() {
        val original = MigrationAction.DeclarePolicy(
            "telemetry-retention", "^telemetry$", mapOf("max-age" to ArgumentValue.Text("7D"), "max-length-bytes" to ArgumentValue.Whole(5)), "uat", "streams", -2,
        )
        val form = assertIs<PolicyForm>(StepForms.formOf(original))
        assertEquals(listOf(original), form.actions(FormContext(TopologyPlan(vhost = "uat"), NamingStyle.KEBAB)))
    }

    @Test
    fun `all six targets open`() {
        PolicyTarget.entries.forEach { target ->
            val entry = PolicyKeyCatalog.offeredFor(target).first()
            val value = when (entry.kind) {
                is PolicyValueKind.WholeNumber -> ArgumentValue.Whole(7)
                PolicyValueKind.Duration -> ArgumentValue.Text("7D")
                PolicyValueKind.Text -> ArgumentValue.Text("x")
                is PolicyValueKind.Choice -> ArgumentValue.Text((entry.kind as PolicyValueKind.Choice).values.first().wire)
            }
            val action = policy(applyTo = target.wire, definition = mapOf(entry.key to value))
            assertIs<PolicyForm>(StepForms.formOf(action), "$target")
        }
    }

    @Test
    fun `an applyTo that is none of the six makes the policy read-only`() {
        listOf("super_streams", "Queues", "", "queues ", "quorum").forEach { assertNull(StepForms.formOf(policy(applyTo = it)), "\"$it\"") }
    }

    @Test
    fun `a key outside the catalog makes the policy read-only`() {
        assertNull(StepForms.formOf(policy(definition = mapOf("x-made-up" to ArgumentValue.Whole(1)))))
        assertNull(StepForms.formOf(policy(definition = mapOf("max-length" to ArgumentValue.Whole(1), "ha-mode" to ArgumentValue.Text("all")))))
    }

    @Test
    fun `a key the target does not offer makes the policy read-only (the file keeps it as it is)`() {
        assertNull(StepForms.formOf(policy(applyTo = "streams", definition = mapOf("max-age" to ArgumentValue.Text("7D"), "delivery-limit" to ArgumentValue.Whole(5)))))
        assertNull(StepForms.formOf(policy(applyTo = "quorum_queues", definition = mapOf("max-age" to ArgumentValue.Text("7D")))))
    }

    @Test
    fun `a value of another type makes the policy read-only`() {
        assertNull(StepForms.formOf(policy(definition = mapOf("max-length" to ArgumentValue.Text("1000")))), "a number as text")
        assertNull(StepForms.formOf(policy(applyTo = "streams", definition = mapOf("max-age" to ArgumentValue.Whole(7)))), "max-age as a number (a 500 on the broker)")
        assertNull(StepForms.formOf(policy(definition = mapOf("max-length" to ArgumentValue.Decimal(1.5)))))
        assertNull(StepForms.formOf(policy(definition = mapOf("max-length" to ArgumentValue.Flag(true)))))
        assertNull(StepForms.formOf(policy(definition = mapOf("dead-letter-exchange" to ArgumentValue.Whole(5)))))
        assertNull(StepForms.formOf(policy(definition = mapOf("max-length" to ArgumentValue.Table(emptyMap())))))
        assertNull(StepForms.formOf(policy(definition = mapOf("max-length" to ArgumentValue.Items(listOf(ArgumentValue.Whole(1)))))))
        assertNull(StepForms.formOf(policy(definition = mapOf("max-length" to ArgumentValue.Nothing))))
    }

    @Test
    fun `a choice whose value is not an offered wire makes the policy read-only (the dropdown cannot hold it without changing the file)`() {
        assertNull(StepForms.formOf(policy(definition = mapOf("overflow" to ArgumentValue.Text("bogus")))), "overflow: bogus")
        assertNull(StepForms.formOf(policy(definition = mapOf("overflow" to ArgumentValue.Text("Reject-Publish")))), "a wire in another case")
        assertIs<PolicyForm>(StepForms.formOf(policy(definition = mapOf("overflow" to ArgumentValue.Text("reject-publish")))), "an offered wire opens")
    }

    @Test
    fun `operator policies and deletes stay read-only`() {
        assertNull(StepForms.formOf(MigrationAction.DeclareOperatorPolicy("op", "^orders", mapOf("max-length" to ArgumentValue.Whole(5)))))
        assertNull(StepForms.formOf(MigrationAction.DeletePolicy("orders-limits")))
    }

    @Test
    fun `a pattern is not looked at, an unevaluable one still opens`() {
        assertIs<PolicyForm>(StepForms.formOf(policy(pattern = "(?=orders)")))
        assertIs<PolicyForm>(StepForms.formOf(policy(pattern = "")))
    }

    @Test
    fun `a policy for another virtual host opens, the form keeps the plan's host when it builds again`() {
        val form = assertIs<PolicyForm>(StepForms.formOf(policy()))
        assertEquals("uat", (form.actions(FormContext(TopologyPlan(vhost = "uat"), NamingStyle.KEBAB)).single() as MigrationAction.DeclarePolicy).vhost)
    }
}

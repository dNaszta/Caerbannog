package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.json.ArgumentValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** M8-01 architecture guard: no String field holds a hash; no model type is @Serializable. Java reflection (core has no kotlin-reflect). */
class DefinitionsModelNoSecretStringTest {
    private val modelClasses: List<Class<*>> = listOf(
        DefinitionsDocument::class, VirtualHostDefinition::class, QueueDefinition::class, ExchangeDefinition::class, BindingDefinition::class,
        PolicyDefinition::class, OperatorPolicyDefinition::class, UserDefinition::class, PermissionDefinition::class,
        TopicPermissionDefinition::class, UnparsedParameter::class, SecretValue::class, UnassignedDefinitions::class,
        DefinitionsParseResult.Parsed::class, DefinitionsParseResult.NeedsVirtualHost::class, DefinitionsParseResult.Rejected::class,
        VirtualHostAssignment.Assigned::class, ArgumentValue.Text::class, ArgumentValue.Table::class,
    ).map { it.java }

    // "hashingAlgorithm" is the algorithm NAME (text, not a secret).
    private fun mentionsSecret(name: String) =
        !name.contains("algorithm", ignoreCase = true) && listOf("hash", "password", "secret").any { name.contains(it, ignoreCase = true) }

    @Test
    fun `no field whose name mentions a hash or secret is a String`() {
        val offenders = modelClasses.flatMap { c ->
            c.declaredFields.filter { mentionsSecret(it.name) && it.type == String::class.java }.map { "${c.simpleName}.${it.name}" }
        }
        assertTrue(offenders.isEmpty(), "String fields that may hold a secret: $offenders")
    }

    @Test
    fun `the user hash field is a SecretValue`() {
        val field = UserDefinition::class.java.declaredFields.single { it.name == "passwordHash" }
        assertEquals(SecretValue::class.java, field.type)
    }

    @Test
    fun `no model type is annotated Serializable`() {
        val offenders = modelClasses.filter { c -> c.annotations.any { it.annotationClass.qualifiedName?.contains("Serializable") == true } }
        assertTrue(offenders.isEmpty(), "Serializable model types: ${offenders.map { it.simpleName }}")
    }
}

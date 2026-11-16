package dev.caerbannog.core.migrations.fakes

import dev.caerbannog.core.migrations.BrokerFinding
import dev.caerbannog.core.migrations.DeclareCheck
import dev.caerbannog.core.migrations.MigrationAction
import java.util.Collections

/**
 * M9-05: the read-only port [DeclareCheck] in memory, scripted per action. Honest in the ways that matter to `PlanCheck`:
 *  - it answers ONLY what the test scripted. A call nobody scripted throws [IllegalStateException] naming the action, so a check that reads
 *    something it should not (or that invents a probe) fails loudly instead of getting a friendly default. It holds no broker table and no
 *    comparison rule: whether a queue is "the same" is the matcher's business (`ManagementDeclareCheck`, tested in data-rabbit);
 *  - every call is recorded in [calls], in order, before it is answered, also a call that then throws or is cancelled;
 *  - an answer can be a suspending block ([answerWith]), so a test can hold a read, cancel it, or let it fail.
 * [answerNamed] matches the resource name of a declare or delete action (queue, exchange, stream, policy), so the probe a check makes for an end
 * of a binding (a declare of that name) is scripted without building the exact action; [answer] matches one exact action.
 */
class FakeDeclareCheck : DeclareCheck {
    private val exact = Collections.synchronizedMap(LinkedHashMap<MigrationAction, suspend (MigrationAction) -> BrokerFinding>())
    private val named = Collections.synchronizedMap(LinkedHashMap<String, suspend (MigrationAction) -> BrokerFinding>())

    /** Every action [find] was asked about, in order. */
    val calls: MutableList<MigrationAction> = Collections.synchronizedList(ArrayList())

    fun answer(action: MigrationAction, finding: BrokerFinding): FakeDeclareCheck = apply { exact[action] = { finding } }

    fun answerNamed(name: String, finding: BrokerFinding): FakeDeclareCheck = apply { named[name] = { finding } }

    fun answerWith(name: String, block: suspend (MigrationAction) -> BrokerFinding): FakeDeclareCheck = apply { named[name] = block }

    override suspend fun find(action: MigrationAction): BrokerFinding {
        calls += action
        val script = exact[action] ?: nameOf(action)?.let { named[it] }
            ?: throw IllegalStateException("FakeDeclareCheck: nothing scripted for \"${action.description}\" (${action.actionName}); scripted: ${named.keys} and ${exact.size} exact")
        return script(action)
    }

    private fun nameOf(action: MigrationAction): String? = when (action) {
        is MigrationAction.DeclareQueue -> action.name
        is MigrationAction.DeleteQueue -> action.name
        is MigrationAction.DeclareExchange -> action.name
        is MigrationAction.DeleteExchange -> action.name
        is MigrationAction.DeclareStream -> action.name
        is MigrationAction.DeleteStream -> action.name
        is MigrationAction.DeclarePolicy -> action.name
        is MigrationAction.DeletePolicy -> action.name
        is MigrationAction.DeclareOperatorPolicy -> action.name
        else -> null
    }
}

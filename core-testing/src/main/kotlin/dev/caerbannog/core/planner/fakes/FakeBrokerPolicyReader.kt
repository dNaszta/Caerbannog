package dev.caerbannog.core.planner.fakes

import dev.caerbannog.core.planner.BrokerPolicyReader
import dev.caerbannog.core.planner.PolicyReadResult
import dev.caerbannog.core.topology.PolicyRow
import java.util.Collections

/**
 * M9-08 step 2: the planner's read port for ONE policy of a virtual host ([BrokerPolicyReader]), in memory. Honest in the ways that matter:
 *  - every call is recorded in [calls] as (virtual host, policy name), in order, also a call that finds nothing: "at most 20 reads", "one read per
 *    distinct policy" and "a plan without a policy step reads nothing" are facts a test can state;
 *  - it answers only what the test put there; a policy nobody put is what the broker says for a missing item (a 404): [PolicyReadResult.Unreadable].
 *    [unreadable] makes a policy that exists answer Unreadable as well (403, no answer);
 *  - the port reads ONE item by its name: there is no list method, so a note built from "all policies" cannot pass through this fake.
 * The real implementation (data-rabbit `ManagementBrokerPolicyReader`) sends `GET /api/policies/{vhost}/{name}` only; its own test asserts the paths.
 */
class FakeBrokerPolicyReader : BrokerPolicyReader {
    private val rows = Collections.synchronizedMap(LinkedHashMap<Pair<String, String>, PolicyRow>())
    private val refused = Collections.synchronizedSet(HashSet<Pair<String, String>>())

    /** Every call, in order. */
    val calls: MutableList<Pair<String, String>> = Collections.synchronizedList(ArrayList())

    fun policy(row: PolicyRow): FakeBrokerPolicyReader = apply { rows[row.vhost to row.name] = row; refused -= (row.vhost to row.name) }

    fun unreadable(vhost: String, name: String): FakeBrokerPolicyReader = apply { refused += (vhost to name) }

    override suspend fun read(vhost: String, name: String): PolicyReadResult {
        calls += (vhost to name)
        if ((vhost to name) in refused) return PolicyReadResult.Unreadable
        return rows[vhost to name]?.let { PolicyReadResult.Found(it) } ?: PolicyReadResult.Unreadable
    }
}

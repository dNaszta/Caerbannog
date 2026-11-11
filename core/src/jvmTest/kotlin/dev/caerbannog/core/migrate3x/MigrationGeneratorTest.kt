package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.topology.BindingDestinationType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** M8-05: each proposal type applied as specified, the order rule, versions, skipped and unwritable items, notes. */
class MigrationGeneratorTest {

    private fun oneClassic(vararg args: Pair<String, Any>, autoDelete: Boolean = false, durable: Boolean = true) =
        started(D.document(queues = listOf(D.classicQueue("orders", autoDelete = autoDelete, durable = durable, arguments = D.args(*args)))))

    private fun Started.keepClassic(name: String = "orders") =
        decide(q(name), ItemDecision.Accept(mapOf(findingId("classic-not-replicated", name) to alternativeIndex(name))))

    /** The index of the keep-classic proposal in `[proposal] + alternatives` of the classic-not-replicated finding. */
    private fun Started.alternativeIndex(name: String): Int =
        report.only("classic-not-replicated", name).allProposals().indexOf(Proposal.KeepClassic).also { assertTrue(it >= 0, "no KeepClassic proposal") }

    private fun Started.convertIndex(name: String): Int =
        report.only("classic-not-replicated", name).allProposals().indexOfFirst { it is Proposal.ConvertQueueType }

    private fun Started.convert(name: String = "orders") =
        decide(q(name), ItemDecision.Accept(mapOf(findingId("classic-not-replicated", name) to convertIndex(name)), TICK))

    // ---- queue type proposals ----

    @Test
    fun `the default converts a classic queue to quorum and keeps the source flags and typed arguments`() {
        val s = oneClassic("x-max-length" to 1000, "x-message-ttl" to 60000, "x-dead-letter-exchange" to "dlx", "x-flag" to true, "x-ratio" to 1.5)
        s.acceptWithTick(q("orders"))
        assertEquals(
            MigrationAction.DeclareQueue(
                "orders", "quorum", "/", durable = true, autoDelete = false,
                arguments = mapOf(
                    "x-max-length" to ArgumentValue.Whole(1000), "x-message-ttl" to ArgumentValue.Whole(60000),
                    "x-dead-letter-exchange" to ArgumentValue.Text("dlx"), "x-flag" to ArgumentValue.Flag(true), "x-ratio" to ArgumentValue.Decimal(1.5),
                ),
            ),
            s.generated().queue("orders"),
        )
    }

    @Test
    fun `keeping classic declares a classic queue`() {
        val s = oneClassic("x-max-length" to 10)
        s.keepClassic()
        val queue = s.generated().queue("orders")
        assertEquals("classic", queue.type)
        assertEquals(mapOf("x-max-length" to w(10)), queue.arguments)
    }

    @Test
    fun `x-queue-type is never an argument, whatever the source says`() {
        val s = started(D.document(queues = listOf(D.classicQueue("a", arguments = D.args("x-queue-type" to "classic")), D.quorumQueue("b", arguments = D.args("x-queue-type" to "quorum")))))
        s.acceptWithTick(q("a"))
        val result = s.generated()
        assertEquals(listOf("a", "b"), result.actions().filterIsInstance<MigrationAction.DeclareQueue>().map { it.name }.sorted())
        result.actions().filterIsInstance<MigrationAction.DeclareQueue>().forEach { assertFalse("x-queue-type" in it.arguments, "${it.name} carries x-queue-type") }
    }

    @Test
    fun `x-queue-version is never added`() {
        val converted = oneClassic().also { it.acceptWithTick(q("orders")) }.generated().queue("orders")
        val kept = oneClassic().also { it.keepClassic() }.generated().queue("orders")
        assertFalse("x-queue-version" in converted.arguments, "converted: ${converted.arguments}")
        assertFalse("x-queue-version" in kept.arguments, "kept classic: ${kept.arguments}")
    }

    @Test
    fun `a source x-queue-version 2 is kept only on a classic queue that stays classic`() {
        val kept = oneClassic("x-queue-version" to 2).also { it.keepClassic() }.generated().queue("orders")
        assertEquals(w(2), kept.arguments["x-queue-version"])
        val converted = oneClassic("x-queue-version" to 2).also { it.convert() }.generated().queue("orders")
        assertFalse("x-queue-version" in converted.arguments, "a quorum queue rejects it: ${converted.arguments}")
    }

    @Test
    fun `a source x-queue-version 1 becomes 2 on a classic queue that stays classic and is dropped on a quorum queue`() {
        val kept = oneClassic("x-queue-version" to 1).also { it.keepClassic() }.generated().queue("orders")
        assertEquals(w(2), kept.arguments["x-queue-version"])
        val converted = oneClassic("x-queue-version" to 1).also { it.convert() }.generated().queue("orders")
        assertFalse("x-queue-version" in converted.arguments, "converted: ${converted.arguments}")
    }

    @Test
    fun `a stream is declared as a stream`() {
        val s = started(D.document(queues = listOf(D.streamQueue("events"))))
        assertEquals(listOf<MigrationAction>(MigrationAction.DeclareStream("events", "/")), s.generated().actions())
    }

    // ---- argument proposals ----

    @Test
    fun `a RemoveArgument that always applies is applied to a converted and to a kept queue`() {
        for (keep in listOf(false, true)) {
            val s = oneClassic("x-queue-master-locator" to "min-masters", "x-max-length" to 5)
            if (keep) s.keepClassic() else s.convert()
            assertEquals(mapOf("x-max-length" to w(5)), s.generated().queue("orders").arguments, "keep=$keep")
        }
    }

    @Test
    fun `a ConvertedToQuorum removal applies only when the queue ends quorum`() {
        val converted = oneClassic("x-max-priority" to 5, "x-queue-mode" to "lazy").also { it.convert() }.generated().queue("orders")
        assertEquals("quorum", converted.type)
        assertEquals(emptyMap<String, ArgumentValue>(), converted.arguments)
        val kept = oneClassic("x-max-priority" to 5, "x-queue-mode" to "lazy").also { it.keepClassic() }.generated().queue("orders")
        assertEquals("classic", kept.type)
        assertEquals(mapOf("x-max-priority" to w(5), "x-queue-mode" to t("lazy")), kept.arguments)
    }

    @Test
    fun `a KeptClassic removal applies only when the queue stays classic`() {
        val kept = oneClassic("x-max-in-memory-length" to 100).also { it.keepClassic() }.generated().queue("orders")
        assertEquals(emptyMap<String, ArgumentValue>(), kept.arguments)
        val converted = oneClassic("x-max-in-memory-length" to 100).also { it.convert() }.generated().queue("orders")
        assertEquals(mapOf("x-max-in-memory-length" to w(100)), converted.arguments, "a quorum queue accepts it")
    }

    @Test
    fun `ClearAutoDelete applies only when the queue ends quorum`() {
        val converted = oneClassic(autoDelete = true).also { it.convert() }.generated().queue("orders")
        assertFalse(converted.autoDelete, "a quorum queue cannot be auto-delete")
        val kept = oneClassic(autoDelete = true).also { it.keepClassic() }.generated().queue("orders")
        assertTrue(kept.autoDelete, "a classic queue stays as in the source")
    }

    @Test
    fun `MakeDurable makes the queue durable when the user chooses it`() {
        val s = oneClassic(durable = false)
        val id = s.findingId("queue-not-durable", "orders")
        s.decide(q("orders"), ItemDecision.Accept(mapOf(id to 1), TICK))
        assertTrue(s.generated().queue("orders").durable)
    }

    @Test
    fun `a SetArgument and a RemoveArgument of a finding that always applies (synthetic report)`() {
        val doc = D.document(queues = listOf(D.classicQueue("orders", arguments = D.args("a" to 1, "b" to 2))))
        val report = syntheticReport(
            synthetic(q("orders"), proposal = Proposal.SetArgument("a", w(9)), rule = "set"),
            synthetic(q("orders"), proposal = Proposal.RemoveArgument("b"), rule = "remove", severity = Severity.Blocker, findingClass = FindingClass.Incompatibility),
        )
        val review = ConversionReview.start(doc, report)
        assertEquals(DecisionResult.Decided, review.decide(q("orders"), ItemDecision.Accept()))
        val result = review.generate(REQUEST) as GenerationResult.Generated
        assertEquals(mapOf("a" to w(9)), result.queue("orders").arguments)
    }

    @Test
    fun `a proposal chosen from the alternatives is the one applied`() {
        val doc = D.document(queues = listOf(D.classicQueue("orders", arguments = D.args("a" to 1))))
        val finding = synthetic(q("orders"), proposal = Proposal.None, alternatives = listOf(Proposal.RemoveArgument("a")), rule = "alt")
        val review = ConversionReview.start(doc, syntheticReport(finding))
        assertEquals(DecisionResult.Decided, review.decide(q("orders"), ItemDecision.Accept(mapOf(finding.id to 1))))
        assertEquals(emptyMap<String, ArgumentValue>(), (review.generate(REQUEST) as GenerationResult.Generated).queue("orders").arguments)
    }

    // ---- exchanges, policies, bindings ----

    @Test
    fun `an exchange keeps its flags, type and typed arguments`() {
        val exchange = ExchangeDefinition("/", "events", "topic", durable = true, autoDelete = true, internal = true, arguments = D.args("alternate-exchange" to "ae", "n" to 3))
        val actions = started(D.document(exchanges = listOf(exchange))).generated().actions()
        assertEquals(
            listOf<MigrationAction>(MigrationAction.DeclareExchange("events", "topic", "/", durable = true, autoDelete = true, internal = true, arguments = mapOf("alternate-exchange" to t("ae"), "n" to w(3)))),
            actions,
        )
    }

    @Test
    fun `a plugin exchange type that was accepted is declared as in the source`() {
        val s = started(D.document(exchanges = listOf(D.exchange("delayed", "x-delayed-message"))))
        s.decide(ex("delayed"), ItemDecision.Accept())
        assertEquals("x-delayed-message", s.generated().actions().filterIsInstance<MigrationAction.DeclareExchange>().single().type)
    }

    @Test
    fun `a policy and an operator policy keep pattern, apply-to, priority and typed definition`() {
        val s = started(
            D.document(
                policies = listOf(D.policy("limits", "^orders\\..*", D.args("max-length" to 1000, "message-ttl" to 5.0), applyTo = "queues", priority = 7)),
                operatorPolicies = listOf(D.operatorPolicy("op", "^x$", D.args("max-length" to 10), applyTo = "classic_queues", priority = 2)),
            ),
        )
        assertEquals(
            listOf(
                MigrationAction.DeclarePolicy("limits", "^orders\\..*", mapOf("max-length" to w(1000), "message-ttl" to ArgumentValue.Decimal(5.0)), "/", "queues", 7),
                MigrationAction.DeclareOperatorPolicy("op", "^x$", mapOf("max-length" to w(10)), "/", "classic_queues", 2),
            ),
            s.generated().actions(),
        )
    }

    @Test
    fun `a policy is written without the keys of an accepted DropPolicyKeys and keeps the others`() {
        val s = started(D.document(policies = listOf(D.policy("limits", "^a$", D.args("ha-mode" to "all", "ha-sync-mode" to "automatic", "max-length" to 1000)))))
        s.decide(pol("limits"), ItemDecision.Accept())
        assertEquals(mapOf("max-length" to w(1000)), s.generated().policy("limits")?.definition)
    }

    @Test
    fun `an operator policy drops its rejected keys`() {
        val s = started(D.document(operatorPolicies = listOf(D.operatorPolicy("op", "^a$", D.args("queue-mode" to "lazy", "max-length" to 10)))))
        s.decide(opPol("op"), ItemDecision.Accept())
        val written = s.generated().actions().filterIsInstance<MigrationAction.DeclareOperatorPolicy>().single()
        assertEquals(mapOf("max-length" to w(10)), written.definition)
    }

    @Test
    fun `a policy that only holds mirroring keys is not written and a note says so`() {
        val s = started(D.document(queues = listOf(D.quorumQueue("keep")), policies = listOf(D.policy("ha-all", "^a$", D.args("ha-mode" to "all", "ha-sync-mode" to "automatic")))))
        s.decide(pol("ha-all"), ItemDecision.Accept())
        val result = s.generated()
        assertEquals(null, result.policy("ha-all"), "the policy must not be written")
        assertEquals(listOf("keep"), result.actions().filterIsInstance<MigrationAction.DeclareQueue>().map { it.name }, "the rest of the file is intact")
        val note = result.notes.filter { it.item == pol("ha-all") }
        assertEquals(1, note.size, "notes: ${result.noteTexts()}")
        assertTrue(note.single().text.contains("held only keys that 4.x rejects"), note.single().text)
        assertFalse(result.skipped.any { it.item == pol("ha-all") }, "an emptied policy is not a skipped item")
    }

    @Test
    fun `a binding keeps the routing key, the destination type and typed arguments`() {
        val toQueue = BindingDefinition("/", "events", "orders", BindingTarget.Queue, "order.#", D.args("x-match" to "all", "n" to 4))
        val toExchange = BindingDefinition("/", "events", "audit", BindingTarget.Exchange, "", emptyMap())
        val s = started(D.document(queues = listOf(D.quorumQueue("orders")), exchanges = listOf(D.exchange("events"), D.exchange("audit")), bindings = listOf(toQueue, toExchange)))
        val bindings = s.generated().actions().filterIsInstance<MigrationAction.DeclareBinding>()
        assertEquals(
            listOf(
                MigrationAction.DeclareBinding("events", "orders", "order.#", "/", BindingDestinationType.QUEUE, mapOf("x-match" to t("all"), "n" to w(4))),
                MigrationAction.DeclareBinding("events", "audit", "", "/", BindingDestinationType.EXCHANGE, emptyMap()),
            ),
            bindings,
        )
    }

    // ---- bindings whose end is not written ----

    private fun twoEndsAndBinding() = started(
        D.document(queues = listOf(D.classicQueue("orders")), exchanges = listOf(D.exchange("events")), bindings = listOf(D.binding("events", "orders", "k"))),
    )

    @Test
    fun `a binding to a skipped queue is left out and listed, a binding from a skipped exchange too`() {
        val s = started(
            D.document(
                queues = listOf(D.classicQueue("orders"), D.quorumQueue("ok")), exchanges = listOf(D.exchange("events", "x-custom"), D.exchange("plain")),
                bindings = listOf(D.binding("events", "ok", "a"), D.binding("plain", "orders", "b"), D.binding("plain", "ok", "c")),
            ),
        )
        s.decide(q("orders"), ItemDecision.Skip)
        s.decide(ex("events"), ItemDecision.Skip)
        val result = s.generated()
        assertEquals(listOf<MigrationAction>(MigrationAction.DeclareBinding("plain", "ok", "c", "/")), result.actions().filterIsInstance<MigrationAction.DeclareBinding>())
        val left = result.notes.mapNotNull { it.item }.filter { it.kind == ItemKind.Binding }.toSet()
        assertEquals(setOf(bind("events", "ok", "a"), bind("plain", "orders", "b")), left)
        assertTrue(result.notes.first { it.item == bind("plain", "orders", "b") }.text.let { it.contains("orders") && it.contains("skipped") }, "the note says why: ${result.noteTexts()}")
    }

    @Test
    fun `a binding to a queue replaced by another name is left out, replaced by the same name it stays`() {
        val s = twoEndsAndBinding()
        s.decide(q("orders"), ItemDecision.Replace(listOf(MigrationAction.DeclareQueue("orders", "quorum", arguments = D.args("x-delivery-limit" to 5)))))
        assertEquals(1, s.generated().actions().filterIsInstance<MigrationAction.DeclareBinding>().size, "same name: the binding stays")
        val other = twoEndsAndBinding()
        other.decide(q("orders"), ItemDecision.Replace(listOf(MigrationAction.DeclareQueue("orders-v2", "quorum"))))
        val result = other.generated()
        assertEquals(emptyList<MigrationAction>(), result.actions().filterIsInstance<MigrationAction.DeclareBinding>())
        assertTrue(result.notes.any { it.item == bind("events", "orders", "k") }, "listed: ${result.noteTexts()}")
    }

    @Test
    fun `a binding to an unwritable queue is left out and the other bindings stay`() {
        val s = started(D.document(queues = listOf(D.quorumQueue(".."), D.quorumQueue("ok")), exchanges = listOf(D.exchange("events")), bindings = listOf(D.binding("events", "..", "a"), D.binding("events", "ok", "b"))))
        val result = s.generated()
        assertEquals(listOf("b"), result.actions().filterIsInstance<MigrationAction.DeclareBinding>().map { it.routingKey })
        assertTrue(result.notes.any { it.item == bind("events", "..", "a") }, "listed: ${result.noteTexts()}")
    }

    // ---- replace ----

    @Test
    fun `Replace writes the user's actions instead of the generated ones, in the place of the item`() {
        val s = started(D.document(queues = listOf(D.classicQueue("orders"), D.quorumQueue("jobs")), exchanges = listOf(D.exchange("events"))))
        val own = MigrationAction.DeclareQueue("orders", "quorum", arguments = D.args("x-delivery-limit" to 5))
        s.decide(q("orders"), ItemDecision.Replace(listOf(own)))
        assertEquals(
            listOf<MigrationAction>(MigrationAction.DeclareExchange("events", "direct", "/"), own, MigrationAction.DeclareQueue("jobs", "quorum", "/")),
            s.generated().actions(),
        )
    }

    // ---- order, versions, files ----

    @Test
    fun `actions are written exchanges, queues and streams, policies, operator policies, bindings, each group in document order`() {
        val doc = D.document(
            bindings = listOf(D.binding("e2", "q2", "2"), D.binding("e1", "q1", "1")),
            operatorPolicies = listOf(D.operatorPolicy("op2", "^a$", D.args("max-length" to 1)), D.operatorPolicy("op1", "^a$", D.args("max-length" to 1))),
            policies = listOf(D.policy("p2", "^a$", D.args("max-length" to 1)), D.policy("p1", "^a$", D.args("max-length" to 1))),
            queues = listOf(D.quorumQueue("q2"), D.streamQueue("s1"), D.quorumQueue("q1")),
            exchanges = listOf(D.exchange("e2"), D.exchange("e1")),
        )
        val order = started(doc).generated().actions().map { it.description }
        assertEquals(
            listOf(
                "Declare direct exchange e2", "Declare direct exchange e1",
                "Declare quorum queue q2", "Declare stream s1", "Declare quorum queue q1",
                "Declare policy p2", "Declare policy p1",
                "Declare operator policy op2", "Declare operator policy op1",
                "Bind e2 to q2 with 2", "Bind e1 to q1 with 1",
            ),
            order,
        )
    }

    @Test
    fun `the file has the version of the next free number, four digits, never an existing one`() {
        val s = started(D.document(queues = listOf(D.quorumQueue("a"))))
        val result = s.generated(GenerationRequest(setOf("0001", "0002"), "legacy-eu"))
        assertEquals(listOf("0003"), result.migrations.map { it.file.version })
        assertEquals(listOf("0001"), started(D.document(queues = listOf(D.quorumQueue("a")))).generated().migrations.map { it.file.version })
    }

    @Test
    fun `one migration per virtual host with consecutive versions and the description of the source`() {
        val s = started(D.document(queues = listOf(D.quorumQueue("a"), D.quorumQueue("b", "/orders"))))
        val result = s.generated(GenerationRequest(setOf("0001"), "legacy-eu"))
        assertEquals(setOf("/", "/orders"), result.migrations.map { it.vhost }.toSet())
        assertEquals(setOf("0002", "0003"), result.migrations.map { it.file.version }.toSet())
        result.migrations.forEach {
            assertEquals("Migrated from RabbitMQ 3.x legacy-eu vhost ${it.vhost}", it.file.description)
            assertTrue(it.file.up.all { a -> a.vhost == it.vhost }, "an action of another virtual host in the file of ${it.vhost}")
        }
        assertEquals("b", result.queue("b", "/orders").name)
    }

    @Test
    fun `a virtual host without any action gets no file`() {
        val s = started(D.document(virtualHosts = listOf(D.virtualHost("/"), D.virtualHost("empty")), queues = listOf(D.quorumQueue("a")), users = listOf(D.user("svc")), permissions = listOf(D.permission("empty", "svc"))))
        // M8-12b: "empty" holds no topology, so no topology file; it is declared by the access file (first, empty vhost)
        assertEquals(listOf("", "/"), s.generated().migrations.map { it.vhost })
    }

    @Test
    fun `a virtual host whose only item is skipped gets no file`() {
        val s = started(D.document(queues = listOf(D.classicQueue("a"), D.quorumQueue("b", "/other"))))
        s.decide(q("a"), ItemDecision.Skip)
        assertEquals(listOf("/other"), s.generated().migrations.map { it.vhost })
    }

    // ---- skipped and unwritable ----

    @Test
    fun `a skipped item is listed as skipped by the user and not written`() {
        val s = started(D.document(queues = listOf(D.classicQueue("orders"), D.quorumQueue("jobs"))))
        s.decide(q("orders"), ItemDecision.Skip)
        val result = s.generated()
        assertEquals(listOf(SkippedItem(q("orders"), SkipReason.SkippedByUser)), result.skipped)
        assertEquals(listOf("jobs"), result.actions().filterIsInstance<MigrationAction.DeclareQueue>().map { it.name })
    }

    @Test
    fun `items named dot or dot dot are left out and listed, never dropped silently, other names with dots are written`() {
        val doc = D.document(
            queues = listOf(D.quorumQueue("."), D.quorumQueue(".."), D.quorumQueue("..."), D.quorumQueue("ok")),
            exchanges = listOf(D.exchange("..")), policies = listOf(D.policy("..", "^a$", D.args("max-length" to 1))),
        )
        val result = started(doc).generated()
        assertEquals(
            setOf(SkippedItem(q("."), SkipReason.NameCannotBeWritten), SkippedItem(q(".."), SkipReason.NameCannotBeWritten), SkippedItem(ex(".."), SkipReason.NameCannotBeWritten), SkippedItem(pol(".."), SkipReason.NameCannotBeWritten)),
            result.skipped.toSet(),
        )
        assertEquals(listOf("...", "ok"), result.actions().filterIsInstance<MigrationAction.DeclareQueue>().map { it.name })
    }

    // ---- single virtual host, notes ----

    @Test
    fun `a single virtual host export uses the assigned name and carries the notice (P17)`() {
        val s = started(D.document(queues = listOf(D.quorumQueue("a", "orders")), origin = DefinitionsOrigin.SingleVirtualHost("orders")))
        val result = s.generated()
        assertEquals(listOf("orders"), result.migrations.map { it.vhost })
        assertEquals("Migrated from RabbitMQ 3.x legacy-eu vhost orders", result.migrations.single().file.description)
        assertTrue(
            "This file is an export of one virtual host and holds no users, virtual hosts or permissions; the virtual host \"orders\" must already exist on the 4.x broker" in result.noteTexts(),
            "notes: ${result.noteTexts()}",
        )
    }

    @Test
    fun `a full export has no single-virtual-host notice`() {
        val result = started(D.document(queues = listOf(D.quorumQueue("a")))).generated()
        assertTrue(result.noteTexts().none { it.contains("export of one virtual host") }, "notes: ${result.noteTexts()}")
    }

    @Test
    fun `a message-loss item gets a note with the recommendation, and the migration does not set the index version`() {
        val s = started(D.document(queues = listOf(D.classicQueue("orders"))), D.liveFacts(indexVersions = mapOf(QueueRef("/", "orders") to ClassicIndexVersion.V1)))
        s.acceptWithTick(q("orders"))
        val result = s.generated()
        val note = result.notes.single { it.text.contains("uses the old classic queue index") }
        assertTrue(note.text.contains("orders") && note.text.contains("a new 4.x broker with this migration and a Shovel is the recommended path"), note.text)
        assertFalse("x-queue-version" in result.queue("orders").arguments)
    }

    @Test
    fun `the access findings no longer give the old note, the access data goes into the access file and the topology file holds the queue only`() {
        val s = started(D.document(virtualHosts = listOf(D.virtualHost("/")), queues = listOf(D.quorumQueue("a")), users = listOf(D.user("svc")), permissions = listOf(D.permission("/", "svc"))))
        val access = s.report.findings.count { it.findingClass == FindingClass.Access }
        assertTrue(access >= 3, "the report should have vhost, user and permission findings but has $access")
        val result = s.generated()
        assertEquals(0, result.noteTexts().count { it == "Handled by the access migration (not part of this generation)" }, "the stale note is gone: ${result.noteTexts()}")
        assertEquals(listOf<MigrationAction>(MigrationAction.DeclareQueue("a", "quorum", "/")), result.allActions().filter { !it.isBrokerLevel })
        // "/" is the default virtual host and is not declared (D5); the user and the permissions of "/" are
        assertEquals(listOf("declareUser", "setPermissions"), result.allActions().filter { it.isBrokerLevel }.map { it.actionName })
    }

    // ---- incomplete, determinism ----

    @Test
    fun `while an item is undecided nothing is produced and the pending items are named`() {
        val s = started(D.document(queues = listOf(D.classicQueue("a"), D.classicQueue("b"), D.classicQueue("c"))))
        s.acceptWithTick(q("a"))
        s.decide(q("b"), ItemDecision.Skip)
        assertEquals(GenerationResult.Incomplete(listOf(q("c"))), s.review.generate(REQUEST))
    }

    @Test
    fun `the same document twice gives equal results`() {
        fun run(): GenerationResult.Generated {
            val s = started(fullFixture())
            s.acceptEveryDefault().forEach { s.decide(it, ItemDecision.Skip) }
            return s.generated()
        }
        // A password hash is a SecretValue (no equals), so the access actions of two runs are never equal objects: the files are compared by their text.
        fun GenerationResult.Generated.view() = listOf(
            migrations.map { Triple(it.vhost, it.access, it.file.rawJson) }, migrations.map { it.file.checksum }, skipped, notes, notMigrated, leftOutPermissions, access,
        )
        assertEquals(run().view(), run().view())
    }

    @Test
    fun `reversed document lists give actions in reversed document order`() {
        fun doc(reverse: Boolean) = D.document(
            queues = listOf(D.quorumQueue("q1"), D.quorumQueue("q2"), D.streamQueue("s1")).let { if (reverse) it.reversed() else it },
            exchanges = listOf(D.exchange("e1"), D.exchange("e2")).let { if (reverse) it.reversed() else it },
            policies = listOf(D.policy("p1", "^a$", D.args("max-length" to 1)), D.policy("p2", "^a$", D.args("max-length" to 1))).let { if (reverse) it.reversed() else it },
        )
        val forward = started(doc(false)).generated().actions().map { it.description }
        val backward = started(doc(true)).generated().actions().map { it.description }
        assertEquals(
            listOf("Declare direct exchange e2", "Declare direct exchange e1", "Declare stream s1", "Declare quorum queue q2", "Declare quorum queue q1", "Declare policy p2", "Declare policy p1"),
            backward,
        )
        assertEquals(forward.take(2).reversed() + forward.drop(2).take(3).reversed() + forward.drop(5).reversed(), backward)
    }

    @Test
    fun `an unwritable classic non-durable queue does not stop generation`() {
        val s = started(D.document(queues = listOf(D.classicQueue("..", durable = false), D.quorumQueue("ok"))))
        val result = s.generated()
        assertEquals(listOf(SkippedItem(q(".."), SkipReason.NameCannotBeWritten)), result.skipped)
        assertEquals(listOf("ok"), result.actions().filterIsInstance<MigrationAction.DeclareQueue>().map { it.name })
    }

    @Test
    fun `an unknown queue type is declared with that type and no x-queue-type argument, and a skipped one is listed`() {
        val s = started(D.document(queues = listOf(D.queue("x", QueueKind.Other("x-foo"), arguments = D.args("x-max-length" to 5)), D.queue("y", QueueKind.Other("x-foo")))))
        s.decide(q("x"), ItemDecision.Accept())
        s.decide(q("y"), ItemDecision.Skip)
        val result = s.generated()
        assertEquals(MigrationAction.DeclareQueue("x", "x-foo", "/", arguments = mapOf("x-max-length" to w(5))), result.queue("x"))
        assertEquals(listOf(SkippedItem(q("y"), SkipReason.SkippedByUser)), result.skipped)
    }

    @Test
    fun `versions are the highest numeric existing one plus one, gaps are not filled, non-numeric names are ignored`() {
        val s = started(D.document(queues = listOf(D.quorumQueue("a"))))
        assertEquals(listOf("0004"), s.generated(GenerationRequest(setOf("0001", "0003"), "legacy-eu")).migrations.map { it.file.version })
        assertEquals(listOf("0002"), s.generated(GenerationRequest(setOf("0001", "abc"), "legacy-eu")).migrations.map { it.file.version })
    }

    @Test
    fun `past 9999 generation fails with IllegalStateException`() {
        val s = started(D.document(queues = listOf(D.quorumQueue("a"))))
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) { s.review.generate(GenerationRequest(setOf("9999"), "legacy-eu")) }
    }

    @Test
    fun `files are ordered by virtual host name, slash first, and get consecutive versions in that order`() {
        val slash = started(D.document(queues = listOf(D.quorumQueue("b", "/orders"), D.quorumQueue("a")))).generated()
        assertEquals(listOf("/" to "0001", "/orders" to "0002"), slash.migrations.map { it.vhost to it.file.version })
        val named = started(D.document(queues = listOf(D.quorumQueue("z", "zeta"), D.quorumQueue("a", "alpha")))).generated(GenerationRequest(setOf("abc"), "legacy-eu"))
        assertEquals(listOf("alpha" to "0001", "zeta" to "0002"), named.migrations.map { it.vhost to it.file.version })
    }

    @Test
    fun `a binding to a queue replaced by a policy only is left out`() {
        val s = twoEndsAndBinding()
        s.decide(q("orders"), ItemDecision.Replace(listOf(MigrationAction.DeclarePolicy("p", "^a$", D.args("max-length" to 1)))))
        val result = s.generated()
        assertEquals(emptyList<MigrationAction>(), result.actions().filterIsInstance<MigrationAction.DeclareBinding>())
        assertTrue(result.notes.any { it.item == bind("events", "orders", "k") }, "listed: ${result.noteTexts()}")
    }

    @Test
    fun `generation changes no decision`() {
        val s = oneClassic()
        s.acceptWithTick(q("orders"))
        s.generated()
        assertEquals(ItemDecision.Accept(acknowledged = TICK), s.review.decisionOf(q("orders")))
        assertEquals(emptyList<ReviewItem>(), s.review.pending)
    }
}

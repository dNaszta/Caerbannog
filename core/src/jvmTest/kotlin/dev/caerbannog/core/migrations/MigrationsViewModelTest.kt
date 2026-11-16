package dev.caerbannog.core.migrations

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.QueuePage
import dev.caerbannog.core.topology.QueueRow
import dev.caerbannog.core.topology.TopologyCatalog
import dev.caerbannog.core.topology.VhostRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MigrationsViewModelTest {

    private class FakeTopologyExecutor : TopologyMigrationExecutor {
        val executedActions = mutableListOf<MigrationAction>()
        var failingActionName: String? = null
        var failureReason: String = "Refused by broker"

        override suspend fun execute(action: MigrationAction): ActionResult {
            executedActions += action
            return if (action.actionName == failingActionName) {
                ActionResult.Failed(failureReason)
            } else {
                ActionResult.Success
            }
        }
    }

    private class FakeTopologyCatalog : TopologyCatalog {
        override suspend fun vhosts(): CatalogResult<List<VhostRow>> =
            CatalogResult.Success(listOf(VhostRow(name = "/")))

        override suspend fun queues(vhost: String, page: Int): CatalogResult<QueuePage> =
            CatalogResult.Success(
                QueuePage(
                    rows = listOf(
                        QueueRow(
                            name = "orders",
                            vhost = vhost,
                            type = "quorum",
                            readyMessages = 0,
                            unackedMessages = 0,
                            consumers = 0,
                            leader = "rabbit@node",
                            members = emptyList(),
                            online = emptyList(),
                            policy = null,
                            state = "running",
                        )
                    ),
                    page = 1,
                    pageCount = 1,
                    pageSize = 100,
                    totalCount = 1,
                )
            )

        override suspend fun exchanges(vhost: String): CatalogResult<List<ExchangeRow>> =
            CatalogResult.Success(
                listOf(
                    ExchangeRow(
                        name = "events",
                        vhost = vhost,
                        type = "topic",
                        durable = true,
                        autoDelete = false,
                        internal = false,
                    )
                )
            )

        override suspend fun bindings(vhost: String): CatalogResult<List<BindingRow>> =
            CatalogResult.Success(
                listOf(
                    BindingRow(
                        source = "events",
                        vhost = vhost,
                        destination = "orders",
                        destinationType = dev.caerbannog.core.topology.BindingDestinationType.QUEUE,
                        routingKey = "order.created",
                    )
                )
            )

        override suspend fun queueDetail(vhost: String, name: String): CatalogResult<QueueRow> =
            CatalogResult.Unexpected

        // M9-14: the export reads these three; the same single queue as `queues`, and no policy.
        override suspend fun queueDeclarations(vhost: String): CatalogResult<List<dev.caerbannog.core.topology.QueueDeclaration>> =
            CatalogResult.Success(listOf(dev.caerbannog.core.topology.QueueDeclaration(name = "orders", vhost = vhost, type = "quorum")))

        override suspend fun policies(vhost: String): CatalogResult<List<dev.caerbannog.core.topology.PolicyRow>> = CatalogResult.Success(emptyList())

        override suspend fun operatorPolicies(vhost: String): CatalogResult<List<dev.caerbannog.core.topology.PolicyRow>> = CatalogResult.Success(emptyList())
    }

    private fun sampleJson(version: String, description: String = "Test migration"): String = """
        {
          "version": "$version",
          "description": "$description",
          "up": [
            { "action": "declareQueue", "name": "queue-$version", "type": "quorum" },
            { "action": "declareBinding", "exchange": "events", "queue": "queue-$version", "routingKey": "key-$version" }
          ]
        }
    """.trimIndent()

    @Test
    fun `parse rejects invalid JSON with clear reason`() {
        val result = MigrationParser.parse("not a json at all {")
        assertTrue(result is MigrationValidationResult.Invalid)
        assertEquals("the file is not valid JSON", (result as MigrationValidationResult.Invalid).reason)
    }

    @Test
    fun `parse rejects migration with no version`() {
        val json = """{ "description": "no version", "up": [] }"""
        val result = MigrationParser.parse(json)
        assertTrue(result is MigrationValidationResult.Invalid)
        assertEquals("the file has no version", (result as MigrationValidationResult.Invalid).reason)
    }

    @Test
    fun `parse rejects duplicate version`() {
        val json = sampleJson("0001")
        val result = MigrationParser.parse(json, existingVersions = setOf("0001"))
        assertTrue(result is MigrationValidationResult.Invalid)
        assertEquals("that version already exists", (result as MigrationValidationResult.Invalid).reason)
    }

    @Test
    fun `parse rejects unsupported action`() {
        val json = """
            {
              "version": "0001",
              "up": [ { "action": "unsupportedAction", "name": "foo" } ]
            }
        """.trimIndent()
        val result = MigrationParser.parse(json)
        assertTrue(result is MigrationValidationResult.Invalid)
        assertTrue((result as MigrationValidationResult.Invalid).reason.contains("action is not supported"))
    }

    @Test
    fun `pending migrations are applied in version order and recorded per profile`() = runTest {
        val fileRepo = InMemoryMigrationFileRepository()
        val historyRepo = InMemoryMigrationHistoryRepository()
        val executor = FakeTopologyExecutor()
        val runner = MigrationRunner(historyRepo, executor)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val vm = MigrationsViewModel(
            fileRepository = fileRepo,
            runner = runner,
            profileName = "uat-eu",
            scope = TestScope(testDispatcher),
        )

        fileRepo.importFile(sampleJson("0003"))
        fileRepo.importFile(sampleJson("0001"))
        fileRepo.importFile(sampleJson("0002"))

        vm.loadMigrations()
        testDispatcher.scheduler.advanceUntilIdle()

        val applyResult = vm.applyPending()
        assertTrue(applyResult is MigrationApplyResult.Success)
        assertEquals(listOf("0001", "0002", "0003"), (applyResult as MigrationApplyResult.Success).appliedVersions)

        val appliedUat = historyRepo.getAppliedMigrations("uat-eu")
        assertTrue(appliedUat.containsKey("0001"))
        assertTrue(appliedUat.containsKey("0002"))
        assertTrue(appliedUat.containsKey("0003"))

        // History is kept per connection profile: prod-eu has none
        val appliedProd = historyRepo.getAppliedMigrations("prod-eu")
        assertTrue(appliedProd.isEmpty())
    }

    @Test
    fun `only pending migrations are applied when rerun`() = runTest {
        val fileRepo = InMemoryMigrationFileRepository()
        val historyRepo = InMemoryMigrationHistoryRepository()
        val executor = FakeTopologyExecutor()
        val runner = MigrationRunner(historyRepo, executor)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val vm = MigrationsViewModel(
            fileRepository = fileRepo,
            runner = runner,
            profileName = "uat-eu",
            scope = TestScope(testDispatcher),
        )

        fileRepo.importFile(sampleJson("0001"))
        fileRepo.importFile(sampleJson("0002"))
        vm.applyPending()
        executor.executedActions.clear()

        fileRepo.importFile(sampleJson("0003"))
        val secondApply = vm.applyPending()
        assertTrue(secondApply is MigrationApplyResult.Success)
        assertEquals(listOf("0003"), (secondApply as MigrationApplyResult.Success).appliedVersions)
    }

    @Test
    fun `dry run evaluates action allowances against configure permission and control level`() = runTest {
        val fileRepo = InMemoryMigrationFileRepository()
        val historyRepo = InMemoryMigrationHistoryRepository()
        val executor = FakeTopologyExecutor()
        val runner = MigrationRunner(historyRepo, executor)
        val testDispatcher = StandardTestDispatcher(testScheduler)

        val destructiveJson = """
            {
              "version": "0001",
              "up": [
                { "action": "declareQueue", "name": "orders", "type": "quorum" },
                { "action": "deleteQueue", "name": "legacy" }
              ]
            }
        """.trimIndent()
        val parseResult = MigrationParser.parse(destructiveJson) as MigrationValidationResult.Valid
        val migrationFile = parseResult.migration

        // Case 1: no configure permission -> all blocked
        val dryRunNoPerm = runner.dryRun(migrationFile, hasConfigurePermission = false, TopologyControlLevel.FULL_CONTROL, vhost = "/")
        assertFalse(dryRunNoPerm.canApply)
        assertTrue(dryRunNoPerm.actions.all { !it.allowed })

        // Case 2: Read-only level -> blocked
        val dryRunReadOnly = runner.dryRun(migrationFile, hasConfigurePermission = true, TopologyControlLevel.READ_ONLY, vhost = "/")
        assertFalse(dryRunReadOnly.canApply)

        // Case 3: Declare and edit level -> deleteQueue blocked, declareQueue allowed
        val dryRunDeclareEdit = runner.dryRun(migrationFile, hasConfigurePermission = true, TopologyControlLevel.DECLARE_AND_EDIT, vhost = "/")
        assertFalse(dryRunDeclareEdit.canApply)
        assertTrue(dryRunDeclareEdit.actions[0].allowed)
        assertFalse(dryRunDeclareEdit.actions[1].allowed)

        // Case 4: Full control -> all allowed
        val dryRunFull = runner.dryRun(migrationFile, hasConfigurePermission = true, TopologyControlLevel.FULL_CONTROL, vhost = "/")
        assertTrue(dryRunFull.canApply)
        assertTrue(dryRunFull.actions.all { it.allowed })
    }

    @Test
    fun `failing action stops migration immediately and does not record as applied`() = runTest {
        val fileRepo = InMemoryMigrationFileRepository()
        val historyRepo = InMemoryMigrationHistoryRepository()
        val executor = FakeTopologyExecutor().apply {
            failingActionName = "declareBinding"
            failureReason = "Exchange does not exist"
        }
        val runner = MigrationRunner(historyRepo, executor)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val vm = MigrationsViewModel(
            fileRepository = fileRepo,
            runner = runner,
            profileName = "uat-eu",
            scope = TestScope(testDispatcher),
        )

        fileRepo.importFile(sampleJson("0001"))
        val result = vm.applyPending()

        assertTrue(result is MigrationApplyResult.ActionFailed)
        val failed = result as MigrationApplyResult.ActionFailed
        assertEquals("0001", failed.version)
        assertEquals("declareBinding", failed.action.actionName)
        assertEquals("Exchange does not exist", failed.reason)

        val applied = historyRepo.getAppliedMigrations("uat-eu")
        assertFalse(applied.containsKey("0001"))
    }

    @Test
    fun `modifying an applied migration is flagged and skipped`() = runTest {
        val fileRepo = InMemoryMigrationFileRepository()
        val historyRepo = InMemoryMigrationHistoryRepository()
        val executor = FakeTopologyExecutor()
        val runner = MigrationRunner(historyRepo, executor)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val vm = MigrationsViewModel(
            fileRepository = fileRepo,
            runner = runner,
            profileName = "uat-eu",
            scope = TestScope(testDispatcher),
        )

        fileRepo.importFile(sampleJson("0001"))
        vm.applyPending()

        // Modify 0001 on disk
        val modifiedJson = sampleJson("0001", description = "Modified description")
        fileRepo.updateFile("0001", modifiedJson)

        vm.loadMigrations()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        val entry = state.entries.first { it.file.version == "0001" }
        assertTrue(entry.status is MigrationStatus.Modified)
        assertTrue(state.warningMessage?.contains("modified") == true)

        // Rerunning applyPending will not apply 0001 again
        executor.executedActions.clear()
        val secondApply = vm.applyPending()
        assertTrue(secondApply is MigrationApplyResult.Success)
        assertTrue((secondApply as MigrationApplyResult.Success).appliedVersions.isEmpty())
        assertTrue(executor.executedActions.isEmpty())
    }

    @Test
    fun `export current topology creates next version migration`() = runTest {
        val fileRepo = InMemoryMigrationFileRepository()
        val historyRepo = InMemoryMigrationHistoryRepository()
        val executor = FakeTopologyExecutor()
        val runner = MigrationRunner(historyRepo, executor)
        val topologyCatalog = FakeTopologyCatalog()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val vm = MigrationsViewModel(
            fileRepository = fileRepo,
            runner = runner,
            topologyCatalog = topologyCatalog,
            profileName = "uat-eu",
            scope = TestScope(testDispatcher),
        )

        fileRepo.importFile(sampleJson("0004"))

        val exported = vm.exportCurrentTopology()!!
        assertEquals("0005", exported.version)
        assertTrue(exported.up.any { it is MigrationAction.DeclareQueue && it.name == "orders" })
        assertTrue(exported.up.any { it is MigrationAction.DeclareExchange && it.name == "events" })
        assertTrue(exported.up.any { it is MigrationAction.DeclareBinding && it.exchange == "events" && it.queue == "orders" })
    }
}

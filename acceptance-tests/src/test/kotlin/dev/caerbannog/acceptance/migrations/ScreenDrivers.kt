package dev.caerbannog.acceptance.migrations

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.connections.ConnectionsViewModel
import dev.caerbannog.core.migrations.MigrationsViewModel
import dev.caerbannog.ui.connections.ConnectionsScreen
import dev.caerbannog.ui.migrations.MigrationsScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * M5-08c2: the REAL Compose screens driven through the Compose UI test API (no window is shown), bound to the REAL view models. A scenario step
 * clicks a tagged node, asks whether it is enabled, or reads all the text below a tag. One driver per scenario; [dispose] ends it.
 */
/**
 * `DesktopComposeUiTest` lives only inside `runDesktopComposeUiTest { }`, and a scenario spans many steps, so each driver owns one thread that
 * stays inside that block and runs the step's commands one by one ([exec]). [dispose] ends the thread and the Compose test.
 */
@OptIn(ExperimentalTestApi::class)
internal abstract class ScreenDriver(content: @Composable () -> Unit) {
    private class Command(val run: (DesktopComposeUiTest) -> Any?) {
        val result: CompletableFuture<Any?> = CompletableFuture()
    }

    private val commands = LinkedBlockingQueue<Command>()
    private val stop = Command(run = { null })
    private val started = CompletableFuture<Unit>()
    private val thread = thread(isDaemon = true, name = "compose-screen-driver") {
        try {
            runDesktopComposeUiTest(1280, 900) {
                setContent(content)
                waitForIdle()
                started.complete(Unit)
                while (true) {
                    val command = commands.take()
                    if (command === stop) break
                    try { command.result.complete(command.run(this)) } catch (failure: Throwable) { command.result.completeExceptionally(failure) }
                }
            }
        } catch (failure: Throwable) {
            started.completeExceptionally(failure)
        }
    }

    init { started.get(60, TimeUnit.SECONDS) }

    @Suppress("UNCHECKED_CAST")
    protected fun <T> exec(block: DesktopComposeUiTest.() -> T): T {
        val command = Command(run = { ui -> ui.block() })
        commands.put(command)
        try {
            return command.result.get(60, TimeUnit.SECONDS) as T
        } catch (failure: java.util.concurrent.ExecutionException) {
            throw failure.cause ?: failure
        }
    }

    private fun SemanticsNode.allText(): String {
        val own = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
        return (listOf(own) + children.map { it.allText() }).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** All the text below [tag], or null when nothing is drawn with that tag. */
    fun textOf(tag: String): String? = exec {
        waitForIdle()
        onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.allText()
    }

    fun isDrawn(tag: String): Boolean = textOf(tag) != null

    fun isEnabled(tag: String): Boolean = exec {
        waitForIdle()
        val nodes = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        check(nodes.isNotEmpty()) { "nothing is drawn with the test tag $tag" }
        nodes.first().config.getOrNull(SemanticsProperties.Disabled) == null
    }

    fun click(tag: String) = exec {
        waitForIdle()
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    fun dispose() {
        commands.put(stop)
        thread.join(30_000)
    }
}

@OptIn(ExperimentalTestApi::class)
internal class MigrationsScreenDriver(vm: MigrationsViewModel, scope: CoroutineScope) : ScreenDriver({
    val state by vm.state.collectAsState()
    MigrationsScreen(
        state = state,
        onSelectVersion = vm::selectVersion,
        onApplyPending = { scope.launch { vm.applyPending() } },
        onApplySingle = { version -> scope.launch { vm.applySingle(version) } },
        onImportJson = { json -> scope.launch { vm.importFile(json) } },
        onExportTopology = { scope.launch { vm.exportCurrentTopology() } },
        onUpdateFile = { version, json -> scope.launch { vm.updateFile(version, json) } },
        onValidateAgain = vm::loadMigrations,
        onDismissError = vm::dismissError,
        onChooseFolder = vm::chooseFolder,
        onChangeFolder = vm::changeFolder,
        onRetry = vm::retry,
        onShowFolder = { },
        onStartWithEmptyHistory = vm::startWithEmptyHistory,
        onConfirmEmptyHistory = vm::confirmEmptyHistory,
        onCancelEmptyHistory = vm::cancelEmptyHistory,
        onDismissOutcome = vm::dismissOutcome,
    )
}) {
    /** Everything the screen says in its banners right now, one line per banner. */
    fun bannerText(): String = BANNER_TAGS.mapNotNull { tag -> textOf(tag) }.joinToString("\n")

    private companion object {
        val BANNER_TAGS = listOf(
            "migrations:unavailableBanner", "migrations:historyBanner", "migrations:folderUnavailableBanner", "migrations:readOnlyBanner",
            "migrations:applyingBanner", "migrations:failedBanner", "migrations:conflictBanner", "migrations:notRecordedBanner",
            "migrations:lockedBanner", "migrations:errorBanner", "migrations:chooseFolderPanel",
        )
    }
}

@OptIn(ExperimentalTestApi::class)
internal class ConnectionsScreenDriver(viewModel: ConnectionsViewModel) : ScreenDriver({ ConnectionsScreen(viewModel) }) {

    fun selectFirstProfile() = exec {
        waitForIdle()
        onAllNodes(
            SemanticsMatcher("permission pill") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("permissionPill:") == true },
            useUnmergedTree = true,
        )[0].performClick()
        waitForIdle()
    }

    fun deleteSelectedAndConfirm() = exec {
        onNodeWithText("Delete profile").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Yes").performClick()
        waitForIdle()
    }

    fun renameSelectedTo(oldName: String, newName: String) = exec {
        onNode(hasSetTextAction() and hasText(oldName)).performTextReplacement(newName)
        onNodeWithText("Save").performScrollTo().performClick()
        waitForIdle()
    }
}

/** A step that needs the coroutine world of the view model: runs [block] to completion. */
internal fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

/** The one Migrations screen of the running scenario, shared by the step classes of this package (Cucumber has no DI here). */
internal object MigrationsScreens {
    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
    private var drawn: Pair<MigrationsViewModel, MigrationsScreenDriver>? = null

    fun forViewModel(vm: MigrationsViewModel): MigrationsScreenDriver {
        drawn?.takeIf { it.first === vm }?.let { return it.second }
        drawn?.second?.dispose()
        return MigrationsScreenDriver(vm, scope).also { drawn = vm to it }
    }

    fun endScenario() {
        drawn?.second?.dispose()
        drawn = null
    }
}

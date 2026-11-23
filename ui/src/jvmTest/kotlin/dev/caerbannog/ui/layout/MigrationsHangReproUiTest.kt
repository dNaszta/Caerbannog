package dev.caerbannog.ui.layout

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrations.ActionDryRun
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationDryRun
import dev.caerbannog.core.migrations.MigrationEntry
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationStatus
import dev.caerbannog.core.migrations.MigrationsScreenState
import dev.caerbannog.ui.PinnedFontTheme
import dev.caerbannog.ui.migrations.MigrationsScreen
import dev.caerbannog.ui.planner.node
import kotlin.test.Test
import kotlin.test.fail

/**
 * M2-21b step 1, the "hang": 1000 x 800, drawer stand-in, pinned font, performScrollTo on Validate never returned. Reproduced bounded (this class, before the fix):
 * the worker sat in waitForIdle for 60 s with the render loop sending frames, the root layout ran once, no measure loop. Cause: the stand-in put 304 dp
 * of blank column in front of a screen that already draws its own 280 dp drawer, so the page was 416 dp wide, the buttons were 0 x 0 px (bounds Rect(0,0,0,0))
 * and performScrollTo on a zero-size node never settles. Class: test artefact (a wrong stand-in), not a product layout loop; the screen alone at 1000 scrolls to
 * Validate at once. The scenario runs on a worker thread and the test fails after 60 s with a dump of the threads, so a hang is red and never a bare waitForIdle.
 */
@OptIn(ExperimentalTestApi::class)
class MigrationsHangReproUiTest {
    private fun file(v: String) = MigrationFile(v, "d", listOf(MigrationAction.DeclareQueue("q$v")), "{}", "abc$v")
    private val files = (1..3).map { file("%04d".format(it)) }
    private val state = MigrationsScreenState(
        entries = files.map { MigrationEntry(it, MigrationStatus.Pending) }, selectedVersion = files[1].version, selectedFile = files[1], profileName = "uat-eu",
        dryRun = MigrationDryRun(files[1].version, files[1].up.map { ActionDryRun(it, allowed = true) }, canApply = true),
    )

    private fun bounded(limitMs: Long = 60_000, body: () -> Unit) {
        var failure: Throwable? = null
        val worker = Thread({ try { body() } catch (t: Throwable) { failure = t } }, "bounded-ui-test").apply { isDaemon = true; start() }
        worker.join(limitMs)
        if (worker.isAlive) {
            val dump = Thread.getAllStackTraces().entries.filter { it.key === worker || it.key.name.contains("AWT") || it.key.name.contains("Compose") || it.key.name.contains("test", true) }
                .joinToString("\n") { (t, st) -> "== ${t.name}\n" + st.take(40).joinToString("\n") { "   at $it" } }
            fail("hang: no result after ${limitMs / 1000} s\n$dump")
        }
        failure?.let { throw it }
    }

    @Test
    fun `at 1000 wide a scroll to Validate returns and the button has a size`() = bounded {
        runDesktopComposeUiTest(1000, 800) {
            setContent { PinnedFontTheme { WithDrawerStandIn { MigrationsScreen(state, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}) } } }
            waitForIdle()
            node("migrations:validateBtn").performScrollTo()
            val b = node("migrations:validateBtn").fetchSemanticsNode().boundsInRoot
            if (b.width <= 0f || b.height <= 0f) fail("Validate has no size: $b")
        }
    }
}

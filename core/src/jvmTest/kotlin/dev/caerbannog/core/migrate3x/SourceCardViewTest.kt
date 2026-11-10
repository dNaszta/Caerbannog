package dev.caerbannog.core.migrate3x

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** M8-06c: the pure rule of the source card, the kind of a held analysis and the fixed texts of the screen (the owner's wording is pinned here). */
class SourceCardViewTest {
    private fun live(name: String = "legacy-eu"): HeldAnalysis {
        val file = ScreenFiles.held()
        return HeldAnalysis(name, file.document, file.report, SourceKind.Live)
    }

    @Test
    fun `a file gives an unlocked card with the file label, whatever connection is held`() {
        val held = ScreenFiles.held(label = "legacy.json")
        assertEquals(SourceKind.File, held.kind, "the default of the new parameter")
        val expected = SourceCardView(SourceKind.File, "legacy.json", locked = false, lockText = null, canAnalyseAgain = false)
        assertEquals(expected, ReportViewBuilder.build(held).source)
        assertEquals(expected, ReportViewBuilder.build(held, liveConnectionName = "legacy-eu").source)
        assertEquals("legacy.json", ReportViewBuilder.build(held).fileLabel)
    }

    @Test
    fun `a live analysis of the connected connection is locked, read only and can be analysed again`() {
        val view = ReportViewBuilder.build(live(), liveConnectionName = "legacy-eu")
        assertEquals(SourceCardView(SourceKind.Live, "legacy-eu", locked = true, lockText = "read only", canAnalyseAgain = true), view.source)
        assertEquals("legacy-eu", view.fileLabel)
    }

    @Test
    fun `a live analysis with no connection or another one reads from name (disconnected) and cannot be analysed again`() {
        val expected = SourceCardView(SourceKind.Live, "from legacy-eu (disconnected)", locked = true, lockText = "read only", canAnalyseAgain = false)
        assertEquals(expected, ReportViewBuilder.build(live(), liveConnectionName = null).source)
        assertEquals(expected, ReportViewBuilder.build(live(), liveConnectionName = "other-eu").source)
        assertEquals(expected, ReportViewBuilder.build(live()).source, "the default of the new parameter is no connection")
    }

    @Test
    fun `analyseLive builds the held analysis as Live with the connection name as the label`() = runTest {
        val source = object : DefinitionsSource {
            override val connectionName = "legacy-eu"
            override suspend fun fetch(): FetchResult = FetchResult.Fetched(ScreenFiles.parse(ScreenFiles.mixed), LiveSourceFacts("legacy-eu", "3.13.7"))
        }
        val held = assertIs<LiveAnalysisOutcome.Analysed>(analyseLive(source)).held
        assertEquals(SourceKind.Live, held.kind)
        assertEquals("legacy-eu", held.fileLabel)
    }

    @Test
    fun `the texts of the live source are the ones the owner and the mockup name`() {
        assertEquals("This screen only reads a file or the connected 3.x broker. It sends nothing that changes any broker.", MigrateFromThreeTexts.INFO_LINE)
        assertEquals("Connect to a RabbitMQ 3.x broker to read it live", MigrateFromThreeTexts.LIVE_NOT_AVAILABLE)
        assertEquals("Definitions file", MigrateFromThreeTexts.SOURCE_FILE)
        assertEquals("Live connection", MigrateFromThreeTexts.LIVE_DISABLED)
        assertEquals("Live connection: legacy-eu", MigrateFromThreeTexts.liveOption("legacy-eu"))
        assertEquals("read only", MigrateFromThreeTexts.READ_ONLY)
        assertEquals("from legacy-eu (disconnected)", MigrateFromThreeTexts.liveDisconnected("legacy-eu"))
        assertEquals("Analyse live connection", MigrateFromThreeTexts.ANALYSE_LIVE)
        assertEquals("Analyse again", MigrateFromThreeTexts.ANALYSE_AGAIN)
        assertEquals("Try again", MigrateFromThreeTexts.TRY_AGAIN)
        assertEquals("Reads the definitions of legacy-eu with GET requests. Nothing on the broker is changed.", MigrateFromThreeTexts.analyseLiveHint("legacy-eu"))
        assertEquals("Reading and analysing legacy-eu", MigrateFromThreeTexts.liveLoading("legacy-eu"))
        assertEquals("Reading the broker can take a few seconds per virtual host. Nothing on it is changed.", MigrateFromThreeTexts.LIVE_LOADING_HINT)
        assertEquals("Live connection", SourceOption.LiveDisabled(MigrateFromThreeTexts.LIVE_NOT_AVAILABLE).label)
        assertEquals("Live connection: legacy-eu", SourceOption.Live("legacy-eu").label)
        assertEquals("Definitions file", SourceOption.File.label)
    }

    @Test
    fun `the card has its pill word and the screen texts are constants`() {
        assertEquals("Live", SourceCardView(SourceKind.Live, "x", true, "read only", true).kindLabel)
        assertEquals("File", SourceCardView(SourceKind.File, "x", false, null, false).kindLabel)
        assertEquals("Live", ReportViewBuilder.build(live(), liveConnectionName = "legacy-eu").source.kindLabel)
        assertEquals("File", ReportViewBuilder.build(ScreenFiles.held()).source.kindLabel)
        assertEquals("Nothing was analysed.", MigrateFromThreeTexts.NOTHING_ANALYSED)
        assertEquals("Choose definitions file", MigrateFromThreeTexts.CHOOSE_FILE)
        assertEquals("In the 3.x management UI: Overview, Export definitions. Or: rabbitmqctl export_definitions file.json", MigrateFromThreeTexts.FILE_EXPORT_HINT)
        assertEquals(
            "These queues use the old classic queue index (version 1), which is the 3.13 default, or the broker did not report their index version. " +
                "RabbitMQ 4.3 and later cannot read the old index. The loss cannot be detected after the upgrade. " +
                "This list comes from the broker: queues it reports as version 2 are not listed.",
            MigrateFromThreeTexts.LOSS_DETECTION_LIVE,
        )
    }
}

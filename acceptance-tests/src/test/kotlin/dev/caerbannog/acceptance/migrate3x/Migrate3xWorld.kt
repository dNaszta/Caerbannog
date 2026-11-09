package dev.caerbannog.acceptance.migrate3x

import dev.caerbannog.core.migrate3x.CompatibilityReport
import dev.caerbannog.core.migrate3x.DefinitionsDocument
import dev.caerbannog.core.migrate3x.DefinitionsParseResult
import dev.caerbannog.core.migrate3x.Finding
import dev.caerbannog.core.migrate3x.LiveSourceFacts
import dev.caerbannog.core.migrate3x.VirtualHostAssignment
import dev.caerbannog.core.migrate3x.fakes.DefinitionsDocuments
import io.cucumber.java.Before

/**
 * Scenario state shared by the M8 step classes of this glue package (Cucumber creates one instance per step class and scenario, and there is
 * no dependency injection in this module, so the shared state is one object that a hook resets before every scenario). Scenarios run one
 * after the other, never in parallel. Plain fields, no logic.
 */
internal object Migrate3xWorld {
    var text: String = ""
    var result: DefinitionsParseResult? = null
    var assignment: VirtualHostAssignment? = null

    /** M8-03: a hand-made document (built by the Given steps) used instead of file text. */
    var document: DefinitionsDocument? = null
    var live: LiveSourceFacts? = null
    var report: CompatibilityReport? = null
    var exportedJson: String? = null
    var lastFinding: Finding? = null
    var lastUser: String? = null
    var lastPolicy: String? = null

    fun edit(change: (DefinitionsDocument) -> DefinitionsDocument) {
        document = change(document ?: DefinitionsDocuments.document())
    }

    fun reset() {
        text = ""; result = null; assignment = null; document = null; live = null; report = null; exportedJson = null
        lastFinding = null; lastUser = null; lastPolicy = null
    }
}

class Migrate3xHooks {
    @Before
    fun resetWorld() = Migrate3xWorld.reset()
}

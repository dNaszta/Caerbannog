package dev.caerbannog.core.migrate3x

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class SourceKind { File, Live }

/** What the screen may read live right now; null = no readable 3.x connection is held. Read only: DefinitionsSource has no write method. */
interface LiveSourceProvider {
    val current: StateFlow<DefinitionsSource?>

    companion object {
        /** The default of tests and of a screen without a connection. */
        val None: LiveSourceProvider = object : LiveSourceProvider {
            override val current: StateFlow<DefinitionsSource?> = MutableStateFlow(null)
        }
    }
}

sealed interface SourceOption {
    val label: String

    data object File : SourceOption {
        override val label: String get() = MigrateFromThreeTexts.SOURCE_FILE
    }

    data class Live(val connectionName: String) : SourceOption {
        override val label: String get() = MigrateFromThreeTexts.liveOption(connectionName)
    }

    data class LiveDisabled(val reason: String) : SourceOption {
        override val label: String get() = MigrateFromThreeTexts.LIVE_DISABLED
    }
}

/** [selected] is Live only while a Live option is offered (a vanished 3.x connection turns it back into File). */
data class SourcePicker(val selected: SourceKind, val options: List<SourceOption>)

/** The card of the source in the report header. */
data class SourceCardView(val kind: SourceKind, val label: String, val locked: Boolean, val lockText: String?, val canAnalyseAgain: Boolean) {
    // M8-06c round 1 STUB: the pill word of the card ("Live" or "File").
    val kindLabel: String get() = ""
}

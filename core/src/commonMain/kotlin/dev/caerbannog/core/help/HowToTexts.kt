package dev.caerbannog.core.help

/** M8-14: one tip of a screen. [id] is the tag suffix (`help-tip-<id>`), e.g. `m3x-6`. */
data class HelpText(val id: String, val text: String)

/**
 * M8-14: a how-to of the dialog. [docHeadings] are the `## ` headings of `docs/MIGRATING_FROM_3X.md` it is the short version of;
 * every phrase of [keyPhrases] (a button or term label the steps depend on) must appear in those sections and in [needs], [steps] and [ifWrong].
 */
data class HowTo(
    val id: String,
    val title: String,
    val docHeadings: List<String>,
    val needs: List<String>,
    val steps: List<String>,
    val ifWrong: List<String>,
    val keyPhrases: List<String>,
)

object HowToTexts {
    val ANALYSE_AND_GENERATE: HowTo = HowTo(
        id = "analyse-generate",
        title = "Analyse and generate",
        docHeadings = listOf(
            "1. Analyse a 3.x broker from a definitions file",
            "2. Analyse a live 3.x broker",
            "3. Review the report and generate migration files",
        ),
        needs = listOf(
            "A definitions.json exported from the 3.x broker, or a connection to the 3.x broker whose user has the administrator tag",
        ),
        steps = listOf(
            "Open Migrate from 3.x and choose the source: Definitions file, or Live connection (a connected 3.x broker).",
            "Read the message-loss block at the top, if there is one, and then the report.",
            "Open each item that needs a decision and accept it, skip it or replace it.",
            "Click Generate migration. The first time, choose the folder for your migration files.",
            "Click Open Migrations and apply the files on a 4.x connection.",
        ),
        ifWrong = listOf(
            "\"The file is not a definitions file\": nothing was analysed; choose another Definitions file.",
            "\"This user cannot export definitions\": use another user, or a Definitions file.",
        ),
        keyPhrases = listOf("Definitions file", "Live connection", "message-loss", "Generate migration", "Open Migrations", "administrator"),
    )

    // M8-14b STUB: empty, the real wording is the developer's job (pinned by HowToTextsAppliedAndMovedTest).
    val APPLY_MIGRATIONS: HowTo = HowTo("apply-migrations", "", emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    val MOVE_MESSAGES: HowTo = HowTo("move-messages", "", emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    /** Every how-to of the app. */
    val all: List<HowTo> = listOf(ANALYSE_AND_GENERATE, APPLY_MIGRATIONS, MOVE_MESSAGES)
}

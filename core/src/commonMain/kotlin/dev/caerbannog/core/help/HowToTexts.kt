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

    val APPLY_MIGRATIONS: HowTo = HowTo(
        id = "apply-migrations",
        title = "Apply a migration",
        docHeadings = listOf("4. Apply the migration to a 4.x broker", "5. Migrate users, virtual hosts and permissions"),
        needs = listOf(
            "A connection to the 4.x broker.",
            "For topology files: the configure permission, the level Declare and edit, and the same virtual host as the file.",
            "For the access file: the administrator tag and the level Full control.",
        ),
        steps = listOf(
            "Connect the 4.x broker.",
            "Choose the folder with your migration files.",
            "Select a file and read the dry run: each action, allowed or blocked with its reason.",
            "Click Apply pending.",
            "Check that each file shows applied.",
        ),
        ifWrong = listOf(
            "The run stops at the failing action and does not mark the file applied; fix the cause and apply again.",
            "A conflict means something with that name exists with other settings; the app does not overwrite it.",
        ),
        keyPhrases = listOf("Apply pending", "Full control", "administrator", "dry run", "conflict"),
    )

    val MOVE_MESSAGES: HowTo = HowTo(
        id = "move-messages",
        title = "Move the messages of a queue",
        docHeadings = listOf("6. Move the messages of a queue with a Shovel", "7. Check that a Shovel finished, or remove a failed one"),
        needs = listOf(
            "A live analysis of the 3.x broker and a connection to the 4.x broker.",
            "The destination queue already on the 4.x broker: apply the migration first.",
            "The Shovel plugins enabled on the 4.x broker.",
            "The policymaker and monitoring tags (or administrator) and the level Declare and edit with the configure permission.",
        ),
        steps = listOf(
            "Click Forward messages in the report and pick the queue on its row.",
            "Read the plan; if something is blocked, fix the cause and pick the queue again.",
            "Read the confirmation: the messages are moved and the app cannot undo it. Check the AMQP host and port.",
            "Type the queue name.",
            "Click Forward messages and watch the progress.",
        ),
        ifWrong = listOf(
            "Read the reason shown.",
            "If the shovel could not be removed, use a connection with Full control and click Remove shovel.",
        ),
        keyPhrases = listOf("Forward messages", "Remove shovel", "Full control", "plugins", "confirm", "destination", "moved"),
    )

    /** Every how-to of the app. */
    val all: List<HowTo> = listOf(ANALYSE_AND_GENERATE, APPLY_MIGRATIONS, MOVE_MESSAGES)
}

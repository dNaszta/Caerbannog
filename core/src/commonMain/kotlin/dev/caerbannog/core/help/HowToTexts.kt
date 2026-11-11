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
    // M8-14 STUB: empty, the real wording is the developer's job (pinned by HowToTextsTest).
    val ANALYSE_AND_GENERATE: HowTo = HowTo("analyse-generate", "", emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    /** Every how-to of the app; M8-14b adds the other two. */
    val all: List<HowTo> = listOf(ANALYSE_AND_GENERATE)
}

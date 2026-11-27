package dev.caerbannog.acceptance.accessview

/**
 * M10-04 part B: the one place that turns step text into the text a pattern really has. In a Gherkin STEP text a backslash is not unescaped (`"^ex\\..*"` reaches
 * the step as `^ex\\..*`, two backslashes); only a table cell unescapes `\\` to `\` (so an Examples value arrives with ONE backslash already). The steps that set
 * or compare a pattern call [pattern] on every such argument, never their own replace (architect ruling 3 of step 2 part A).
 */
internal object StepText {
    /** `\\` becomes `\`, left to right, each pair once; a single backslash stays (`\d` is `\d`), so a value that a table cell already unescaped is unchanged. */
    fun pattern(text: String): String = text.replace("\\\\", "\\")
}

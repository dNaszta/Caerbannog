package dev.caerbannog.datarabbit

import dev.caerbannog.core.streams.AppSettings
import dev.caerbannog.core.streams.DEFAULT_REFRESH
import dev.caerbannog.core.streams.RefreshInterval
import java.io.File

/**
 * M2-05a step 2: COMPILE STUB written by the test developer so the tests can be red for the right reason. It has NO
 * behaviour: it never reads and never writes [file]. The senior developer replaces the body (brief "Scope" item 3).
 *
 * Contract the tests pin: `JsonFileAppSettings(file: File, log: (String) -> Unit = <stderr line>)`; `companion fun defaultFile(): File`
 * (`~/.caerbannog/settings.json`); `var streamListRefresh`.
 */
class JsonFileAppSettings(
    @Suppress("unused") private val file: File,
    @Suppress("unused") private val log: (String) -> Unit = { System.err.println(it) },
) : AppSettings {
    override var streamListRefresh: RefreshInterval = DEFAULT_REFRESH

    companion object {
        fun defaultFile(): File = File(System.getProperty("user.home"), ".caerbannog/settings.json")
    }
}

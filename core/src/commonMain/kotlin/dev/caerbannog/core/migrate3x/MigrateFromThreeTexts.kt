package dev.caerbannog.core.migrate3x

/** The fixed words of the Migrate from 3.x screen (owner decision 5: as in `docs/ux/Migrate3x.dc.html`). Never built from file content. */
object MigrateFromThreeTexts {
    const val HASH_HIDDEN: String = "hash hidden"
    const val FILE_UNREADABLE: String = "The file could not be read"
    const val FILE_TOO_LARGE: String = "The file is larger than 64 MB"
    const val SAVE_FAILED_PREFIX: String = "The report could not be saved"
    const val REPORT_COPIED: String = "Report copied as text"
    const val ACCESS_EMPTY: String = "The file holds no users, virtual hosts or permissions, so the access migration is empty."
}

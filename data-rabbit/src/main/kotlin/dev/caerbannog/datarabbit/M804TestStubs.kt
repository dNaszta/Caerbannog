package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.MigrationAction
import kotlinx.serialization.json.JsonObject

// M8-04 TEST-FIRST COMPILE STUB. NOT PRODUCTION CODE. The senior developer deletes this file and writes MigrationRequests.kt.
// The signature is the brief's: `MigrationRequests.requestFor(action): ManagementWrite?`, methods PUT or POST.

internal data class ManagementWrite(val method: String, val path: String, val body: JsonObject?)

internal object MigrationRequests {
    fun requestFor(action: MigrationAction): ManagementWrite? = TODO("M8-04 stub: MigrationRequests.requestFor")
}

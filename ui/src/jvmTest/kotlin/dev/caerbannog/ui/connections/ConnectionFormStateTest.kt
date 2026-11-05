package dev.caerbannog.ui.connections

import dev.caerbannog.core.connections.BrokerPorts
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.DeclaredPermissions
import dev.caerbannog.core.connections.DeclaredPermissionsEdit
import dev.caerbannog.core.connections.TopologyControlLevel
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * M2-14e (R6), no Compose: the edit panel's field holder and its "which CA path do I send" rule.
 *
 * Contract pinned here for the build (names are the test developer's proposal; the senior developer may ask for others):
 * `ConnectionFormState(selected: ConnectionProfile?)` with plain read-write properties `name`, `host`, `virtualHost`, `username`,
 * `password`, `caCertificatePath`, `tls`, `ports: BrokerPorts`, `caEdited`, `caHadFocus`, `caValidatedPath: String?`, and
 * `toInput(saved: ConnectionProfile?): ConnectionProfileInput` (the Save input; the CA rule is applied inside it, against `saved`, the profile as it is stored NOW, not the one the form was created with). It holds no repository and calls no broker.
 */
class ConnectionFormStateTest {

    private val saved = ConnectionProfile(
        name = "uat-eu", host = "rabbit.example", virtualHost = "/", username = "svc",
        streamPort = 5551, managementPort = 15671, tls = true, caCertificatePath = "/ca/old.pem",
    )
    private val savedWithoutCa = saved.copy(caCertificatePath = null)

    private fun newForm() = ConnectionFormState(null).apply { name = "uat-eu"; host = "rabbit.example"; username = "svc" }

    private class Row(val label: String, val selected: ConnectionProfile?, val typed: String, val validated: String?, val tls: Boolean, val sent: String?)

    @Test
    fun `the CA path sent on Save follows the rule`() {
        val rows = listOf(
            Row("new profile, typed path is sent", null, "/ca/new.pem", null, true, "/ca/new.pem"),
            Row("new profile, nothing typed sends the blank", null, "", null, true, ""),
            Row("new profile, typed and already validated is still sent (nothing is stored yet)", null, "/ca/new.pem", "/ca/new.pem", true, "/ca/new.pem"),
            Row("saved profile, unchanged sends null (keep)", saved, "/ca/old.pem", null, true, null),
            Row("saved profile without a path, untouched sends null", savedWithoutCa, "", null, true, null),
            Row("saved profile, changed and not validated sends the path", saved, "/ca/other.pem", null, true, "/ca/other.pem"),
            Row("saved profile, changed and validated on focus loss sends null (already stored)", saved, "/ca/other.pem", "/ca/other.pem", true, null),
            Row("saved profile, changed after the validation sends the new path", saved, "/ca/third.pem", "/ca/other.pem", true, "/ca/third.pem"),
            Row("saved profile that had a path, cleared sends blank (clear)", saved, "", null, true, ""),
            Row("saved profile that had a path, cleared after a validation sends blank", saved, "", "/ca/other.pem", true, ""),
            Row("TLS off still sends the typed path (kept, unused)", saved, "/ca/other.pem", null, false, "/ca/other.pem"),
            Row("TLS off on a new profile still sends the typed path", null, "/ca/new.pem", null, false, "/ca/new.pem"),
        )
        for (row in rows) {
            val form = ConnectionFormState(row.selected)
            form.caCertificatePath = row.typed
            form.caValidatedPath = row.validated
            form.tls = row.tls
            assertEquals(row.sent, form.toInput(row.selected).caCertificatePath, row.label)
        }
    }

    @Test
    fun `a saved profile starts with its own values and nothing edited`() {
        val form = ConnectionFormState(saved)
        assertEquals("uat-eu", form.name)
        assertEquals("rabbit.example", form.host)
        assertEquals("/", form.virtualHost)
        assertEquals("svc", form.username)
        assertEquals("/ca/old.pem", form.caCertificatePath)
        assertEquals(BrokerPorts(5551, 15671), form.ports)
        assertEquals("", form.password)
        assertFalse(form.caEdited)
        assertFalse(form.caHadFocus)
        assertNull(form.caValidatedPath)
    }

    @Test
    fun `a form for a new profile starts with TLS on, the TLS ports and the root virtual host`() {
        val form = ConnectionFormState(null)
        assertEquals(true, form.tls)
        assertEquals(BrokerPorts(5551, 15671), form.ports)
        assertEquals("/", form.virtualHost)
        assertEquals("", form.name)
        assertEquals("", form.caCertificatePath)
    }

    @Test
    fun `a form for another profile starts clean, no edit state leaks from the first one`() {
        val first = ConnectionFormState(saved).apply {
            password = "typed"
            caEdited = true
            caHadFocus = true
            caValidatedPath = "/ca/x.pem"
        }
        val second = ConnectionFormState(saved.copy(name = "uat-us", caCertificatePath = null))
        assertEquals("uat-us", second.name)
        assertEquals("", second.password)
        assertEquals("", second.caCertificatePath)
        assertFalse(second.caEdited)
        assertFalse(second.caHadFocus)
        assertNull(second.caValidatedPath)
        assertEquals("typed", first.password, "the first form is not touched")
    }

    @Test
    fun `a blank password is sent as null (keep the stored one) and a typed one as typed`() {
        val form = ConnectionFormState(saved)
        assertNull(form.toInput(saved).password)
        form.password = "s3cret"
        assertEquals("s3cret", form.toInput(saved).password)
    }

    @Test
    fun `the other fields travel into the input as typed`() {
        val form = newForm().apply { virtualHost = "/qa"; ports = BrokerPorts(5552, 15672); tls = false }
        val input = form.toInput(null)
        assertEquals("uat-eu", input.name)
        assertEquals("rabbit.example", input.host)
        assertEquals("/qa", input.virtualHost)
        assertEquals("svc", input.username)
        assertEquals(5552, input.streamPort)
        assertEquals(15672, input.managementPort)
        assertEquals(false, input.tls)
    }

    @Test
    fun `the level and the declaration travel into the input, declaration off sends Clear`() {
        val form = ConnectionFormState(saved).apply { level = TopologyControlLevel.FULL_CONTROL }
        assertEquals(TopologyControlLevel.FULL_CONTROL, form.toInput(saved).topologyControlLevel)
        assertEquals(DeclaredPermissionsEdit.Clear, form.toInput(saved).declaredPermissions)
        form.declares = true
        form.declaredConfigure = true
        form.declaredWrite = false
        assertEquals(DeclaredPermissionsEdit.Set(DeclaredPermissions(configure = true, write = false)), form.toInput(saved).declaredPermissions)
    }

    @Test
    fun `a new form starts read-only with no declaration`() {
        val form = ConnectionFormState(null)
        assertEquals(TopologyControlLevel.READ_ONLY, form.level)
        assertFalse(form.declares)
    }

    @Test
    fun `after a Save stored the typed CA path a second Save without touching the field sends null`() {
        val form = ConnectionFormState(saved) // created while the stored path was /ca/old.pem
        form.caCertificatePath = "/ca/new.pem"
        assertEquals("/ca/new.pem", form.toInput(saved).caCertificatePath, "setup: changed against the stored path, so it is sent")
        val storedNow = saved.copy(caCertificatePath = "/ca/new.pem") // the first Save stored it; the form is not recreated (same name)
        assertNull(form.toInput(storedNow).caCertificatePath, "the stored path equals the typed one: keep, do not read the file again")
    }

    @Test
    fun `a path that was cleared by a Save is not sent again as blank`() {
        val form = ConnectionFormState(saved)
        form.caCertificatePath = ""
        val storedNow = saved.copy(caCertificatePath = null)
        assertNull(form.toInput(storedNow).caCertificatePath)
    }
}

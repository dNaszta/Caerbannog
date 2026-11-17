package dev.caerbannog.core.publish

import dev.caerbannog.core.connections.fakes.FakeMessagePublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** M9-10b step 2: the write text lives in one function that the AMQP dialog and the test message share; the AMQP dialog says the same words as before. */
class NoWritePermissionTextTest {
    @Test
    fun `the shared text names the virtual host`() {
        assertEquals("Test publishing is disabled: no write permission on virtual host /.", noWritePermission("/"))
        assertEquals("Test publishing is disabled: no write permission on virtual host prod-eu.", noWritePermission("prod-eu"))
    }

    @Test
    fun `the AMQP dialog still says the same words and uses the shared function`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = PublishMessageViewModel(publisher = FakeMessagePublisher(), connectionName = "c", vhost = "prod", writePermission = false, scope = scope)
            assertEquals(PublishGate.Disabled("Test publishing is disabled: no write permission on virtual host prod."), vm.state.value.gate)
            assertEquals(PublishGate.Disabled(noWritePermission("prod")), vm.state.value.gate)
        } finally {
            scope.cancel()
        }
    }
}

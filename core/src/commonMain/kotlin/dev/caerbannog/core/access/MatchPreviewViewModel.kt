package dev.caerbannog.core.access

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * M10-04 part B contract: STUB (test developer, step 2); the senior developer builds it in step 3. Public surface the tests use:
 * `state`, `chooseAction`, `chooseVhost`, `setName`, `setRoutingKey`, `check`. Every method is asynchronous on [scope], like [PermissionsViewModel].
 * Pressing Check calls [matcher] only: no gateway call, no request. [heldSecret] is read per call and never stored (typed text passes [SecretGuard] with it
 * before it is echoed in a verdict). Check with no virtual host chosen does nothing, except when the permissions are not available (locked, token, not loaded):
 * that verdict needs no host, and a locked tab reads no host list.
 */
class MatchPreviewViewModel(
    private val input: StateFlow<MatchInput>,
    private val scope: CoroutineScope,
    private val heldSecret: () -> String? = { null },
    private val matcher: AccessMatcher = AccessMatcher(),
) {
    val state: StateFlow<MatchPreviewState> get() = throw NotImplementedError("M10-04 part B: MatchPreviewViewModel.state")

    fun chooseAction(action: MatchAction): Unit = throw NotImplementedError("M10-04 part B: chooseAction")

    fun chooseVhost(vhost: String): Unit = throw NotImplementedError("M10-04 part B: chooseVhost")

    fun setName(text: String): Unit = throw NotImplementedError("M10-04 part B: setName")

    fun setRoutingKey(text: String): Unit = throw NotImplementedError("M10-04 part B: setRoutingKey")

    fun check(): Unit = throw NotImplementedError("M10-04 part B: check")
}

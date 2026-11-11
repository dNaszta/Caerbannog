package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.FailureKind
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.echoableName

/**
 * Every failure text of the migration executor, fixed in one place. The broker's body and any exception text are never copied; a name from the
 * file is put in only when [echoableName] allows it, the wording stays the same without it.
 */
internal object ExecutorReasons {
    const val NOT_ALLOWED = "the user is not allowed to do this"
    const val NO_ANSWER = "the broker did not answer"
    const val REFUSED = "the broker refused the request"
    const val BINDING_NOT_IDENTIFIED = "the binding could not be identified"
    const val OTHER_BINDING_REMOVED = "a different binding was removed"
    const val NOT_A_REQUEST = "this action cannot be sent to the broker"

    fun failed(reason: String): ActionResult.Failed = ActionResult.Failed(reason)

    fun conflict(action: MigrationAction): ActionResult.Failed =
        ActionResult.Failed("${subject(action)} exists with different settings", FailureKind.Conflict)

    fun notFoundAfterDeclare(action: MigrationAction): ActionResult.Failed = failed("${subject(action)} was not found after it was declared")

    fun differentAfterDeclare(action: MigrationAction): ActionResult.Failed =
        failed("${subject(action)} was found with different settings after it was declared")

    fun stillThereAfterDelete(action: MigrationAction): ActionResult.Failed = failed("${subject(action)} still exists after it was deleted")

    /** What a reply that has no special meaning for the call says: 401 and 403, a broker error or silence, or a refusal. */
    fun forReply(reply: ManagementReply): ActionResult.Failed = when (reply) {
        is ManagementReply.Response -> when {
            reply.status == 401 || reply.status == 403 -> failed(NOT_ALLOWED)
            reply.status >= SERVER_ERROR -> failed(NO_ANSWER)
            else -> failed(REFUSED)
        }
        ManagementReply.Unreachable, ManagementReply.CertificateRejected, ManagementReply.NoTlsAnswer -> failed(NO_ANSWER)
    }

    /** The echoed name of the item, or a generic phrase when it may not be echoed (or a binding has no single name). */
    private fun subject(action: MigrationAction): String = when (action) {
        is MigrationAction.DeclareQueue -> nameOr(action.name)
        is MigrationAction.DeclareExchange -> nameOr(action.name)
        is MigrationAction.DeclareStream -> nameOr(action.name)
        is MigrationAction.DeclarePolicy -> nameOr(action.name)
        is MigrationAction.DeclareOperatorPolicy -> nameOr(action.name)
        is MigrationAction.DeleteQueue -> nameOr(action.name)
        is MigrationAction.DeleteExchange -> nameOr(action.name)
        is MigrationAction.DeleteStream -> nameOr(action.name)
        is MigrationAction.DeletePolicy -> nameOr(action.name)
        is MigrationAction.DeclareBinding, is MigrationAction.DeleteBinding -> "The binding"
        is MigrationAction.DeclareVhost, is MigrationAction.DeclareUser, is MigrationAction.SetPermissions, is MigrationAction.SetTopicPermissions -> "The access item" // M8-12a STUB: the executor is M8-12c
    }

    private fun nameOr(name: String): String = if (echoableName(name)) name else "The resource"

    private const val SERVER_ERROR = 500
}

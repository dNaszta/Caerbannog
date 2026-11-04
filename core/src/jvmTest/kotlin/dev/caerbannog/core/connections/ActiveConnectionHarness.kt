package dev.caerbannog.core.connections

import dev.caerbannog.core.connections.fakes.FakeConnectionGateway

/**
 * M2-03 part 1: shared set-up of the `ActiveConnection` tests. Everything is a fake: no broker, no keychain,
 * no files. The permission checker and the gateway write to ONE log, so the order of "check:<name>",
 * "open:<name>", "opened:<name>#n" and "close:<name>#n" is asserted as the order of Connect (brief, step 1 to 6).
 *
 * Contract used (test developer, see docs/tasks/M2-03-stream-gateway.md Status, part 1 step 2):
 *   ActiveConnection(session: ConnectionSession, gateway: ConnectionGateway)
 *   val state: StateFlow<LinkState>; fun stateOf(name: String): LinkState
 *   suspend fun connect(profile: ConnectionProfile); fun disconnect(); fun close()
 *   fun profileChanged(name: String) / fun profileDeleted(name: String)  (called by ConnectionsViewModel)
 */
internal const val PASSWORD = "S3cret-PW-4711"

internal class LoggingPermissionChecker(
    private val log: MutableList<String>,
    var result: PermissionCheckResult = PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = true),
) : PermissionChecker {
    /** Name and password of every check, in order. The password is the checker's input, never an output. */
    val calls: MutableList<Pair<String, String>> = java.util.Collections.synchronizedList(ArrayList())

    /** Runs inside the check: lets a test look at the app while the broker is asked. */
    @Volatile
    var onCheck: ((ConnectionProfile) -> Unit)? = null

    override suspend fun check(profile: ConnectionProfile, password: String): PermissionCheckResult {
        log += "check:${profile.name}"
        calls += profile.name to password
        onCheck?.invoke(profile)
        return result
    }
}

internal class MapSecrets(passwords: Map<String, String>) : SecretStore {
    private val passwords = passwords.toMutableMap()
    override fun store(profileName: String, password: String) {
        passwords[profileName] = password
    }
    override fun retrieve(profileName: String): String? = passwords[profileName]
    override fun delete(profileName: String) {
        passwords.remove(profileName)
    }
}

internal class Harness {
    val gateway = FakeConnectionGateway()
    val checker = LoggingPermissionChecker(gateway.log)
    val secrets = MapSecrets(mapOf("uat-eu" to PASSWORD, "prod-eu" to PASSWORD))
    val session = ConnectionSession(checker, secrets)
    val active = ActiveConnection(session, gateway)

    val uat = ConnectionProfile(name = "uat-eu", host = "rabbit-uat.example.com", virtualHost = "/", username = "svc")
    val prod = ConnectionProfile(name = "prod-eu", host = "rabbit-prod.example.com", virtualHost = "/", username = "svc")

    /** Everything a reader of the app can see as text, for the "no password anywhere" checks. */
    fun exposedText(): String = listOf(
        active.state.value.toString(),
        session.state.value.toString(),
        gateway.log.toList().toString(),
        gateway.everOpened.toString(),
    ).joinToString("\n")
}

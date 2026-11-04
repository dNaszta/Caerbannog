package dev.caerbannog.core.connections.fakes

import dev.caerbannog.core.connections.ConnectionFailureReason
import dev.caerbannog.core.connections.ConnectionGateway
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.HeldConnection
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.streams.StreamCatalog
import dev.caerbannog.core.streams.StreamOffsets
import dev.caerbannog.core.streams.Visibility
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * M2-03 part 1 test double for [ConnectionGateway]. One source file, compiled into two modules: `core`
 * (jvmTest) and `acceptance-tests` (extra source directory, see its build.gradle.kts), so the unit tests and
 * the Gherkin steps use the same fake. No Docker, no network, no keychain.
 *
 * Honest by design: a failed open leaves nothing open; a cancelled open leaves nothing open; every opened
 * [Held] counts its close calls (a second close is not forgiven, it is counted, so a test can demand exactly
 * one); the order of everything is written to [log]. The fake only ever receives the [ConnectionProfile], never
 * a password, so nothing it exposes can contain one.
 */
class FakeConnectionGateway(
    /** Shared, ordered, thread-safe event log: "open:<name>", "opened:<name>#<n>", "close:<name>#<n>", "failed:<name>", "cancelled:<name>". Tests may append their own entries (e.g. "check:<name>"). */
    val log: MutableList<String> = Collections.synchronizedList(ArrayList()),
) : ConnectionGateway {

    /** What the broker does instead of letting the app in. Each maps to one reason text owned by core. */
    enum class Failure(val reason: String) {
        BROKER_NOT_REACHABLE(ConnectionFailureReason.BROKER_NOT_REACHABLE),
        CERTIFICATE_NOT_TRUSTED(ConnectionFailureReason.CERTIFICATE_NOT_TRUSTED),
        NO_PASSWORD_STORED(ConnectionFailureReason.NO_PASSWORD_STORED),
        CREDENTIALS_REFUSED(ConnectionFailureReason.CREDENTIALS_REFUSED),
    }

    /** One open connection (the holder). [closeCalls] counts calls, so "closed once" is checkable. */
    class Held internal constructor(
        val profileName: String,
        val number: Int,
        /** False when the stream port of the profile did not answer: the connection is still usable (offsets unavailable). */
        val streamPortAnswers: Boolean,
        private val log: MutableList<String>,
        override val catalog: StreamCatalog,
        override val offsets: StreamOffsets,
    ) : HeldConnection {
        private val closes = AtomicInteger()
        val closeCalls: Int get() = closes.get()
        val isClosed: Boolean get() = closes.get() > 0

        override fun close() {
            closes.incrementAndGet()
            log += "close:$profileName#$number"
        }
    }

    /** Makes the opens of one profile wait; [release] lets them (and every later open of that profile) through. */
    class Gate internal constructor() {
        internal val released = CompletableDeferred<Unit>()
        fun release() {
            released.complete(Unit)
        }
    }

    private val failures = ConcurrentHashMap<String, Failure>()
    private val silentStreamPort = ConcurrentHashMap.newKeySet<String>()
    private val gates = ConcurrentHashMap<String, Gate>()
    private val catalogs = ConcurrentHashMap<String, FakeStreamCatalog>()
    private val offsetSources = ConcurrentHashMap<String, FakeStreamOffsets>()
    private val numbers = AtomicInteger()
    private val started = Channel<String>(Channel.UNLIMITED)
    private val opened = Collections.synchronizedList(ArrayList<Held>())

    /** Profile names in the order [open] was called. */
    val openCalls: List<String> get() = synchronized(log) { log.filter { it.startsWith("open:") }.map { it.removePrefix("open:") } }

    /** Called inside [open], before it answers; lets a step look at the app at the moment the broker is asked. */
    @Volatile
    var onOpen: ((ConnectionProfile) -> Unit)? = null

    /** Every connection ever opened, in order. */
    val everOpened: List<Held> get() = synchronized(opened) { opened.toList() }

    /** Connections opened and not closed: what the app still holds on the broker. */
    val heldNow: List<Held> get() = everOpened.filter { !it.isClosed }

    fun failWith(profileName: String, failure: Failure) {
        failures[profileName] = failure
    }

    fun succeed(profileName: String) {
        failures.remove(profileName)
    }

    /** The stream port of [profileName] does not answer: open still succeeds, its offsets read Unavailable. */
    fun streamPortSilent(profileName: String) {
        silentStreamPort += profileName
        offsetsOf(profileName).silent = true
    }

    /** The catalog every connection opened for [profileName] exposes; configure it before Connect. */
    fun catalogOf(profileName: String): FakeStreamCatalog = catalogs.getOrPut(profileName) { FakeStreamCatalog() }

    /** The first-offset source every connection opened for [profileName] exposes. */
    fun offsetsOf(profileName: String): FakeStreamOffsets = offsetSources.getOrPut(profileName) { FakeStreamOffsets() }

    /** The broker answers 401 "Not management user" for the user of [profileName]: Connect still succeeds, visibility is NONE. */
    fun noManagementTag(profileName: String) {
        catalogOf(profileName).visibility = Visibility.NONE
    }

    /** The broker of [profileName] answers only after [Gate.release]. Overlap and progress tests need no sleeps. */
    fun holdOpens(profileName: String): Gate = Gate().also { gates[profileName] = it }

    /** Suspends until the next held open (see [holdOpens]) has started waiting, in call order, and returns its profile name. Bounded. */
    suspend fun awaitOpenStarted(): String = withTimeout(TIMEOUT_MS) { started.receive() }

    override suspend fun open(profile: ConnectionProfile): OpenResult {
        val name = profile.name
        log += "open:$name"
        onOpen?.invoke(profile)
        gates[name]?.let { gate ->
            started.trySend(name)
            try {
                gate.released.await()
            } catch (e: CancellationException) {
                log += "cancelled:$name"
                throw e
            }
        }
        failures[name]?.let {
            log += "failed:$name"
            return OpenResult.Failed(it.reason)
        }
        val held = Held(name, numbers.incrementAndGet(), name !in silentStreamPort, log, catalogOf(name), offsetsOf(name))
        opened += held
        log += "opened:$name#${held.number}"
        return OpenResult.Opened(held)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}

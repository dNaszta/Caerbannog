package dev.caerbannog.acceptance.connections

import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.CaCertificateValidationResult
import dev.caerbannog.core.connections.CaCertificateValidator
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileRepository
import dev.caerbannog.core.connections.Endpoint
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.SecretStore
import java.io.File
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory

/*
 * M1-02 test developer note (see docs/tasks/M1-02-connection-profiles.md, Design notes):
 * these fakes exist so tests/bdd/features/01_connections.feature's step definitions can run with no
 * broker, no real filesystem and no real OS keychain. They reference `dev.caerbannog.core.connections.*`
 * types that do not exist yet in `core` - this file is expected to fail to compile until the senior
 * developer adds ConnectionProfile, TopologyControlLevel, ConnectionProfileRepository,
 * SaveConnectionResult, SecretStore, BrokerReachabilityChecker, ConnectionCheckResult, Endpoint,
 * CaCertificateValidator and CaCertificateValidationResult to `core` (commonMain). The exact
 * package/shape used here (dev.caerbannog.core.connections.*) is this test developer's proposed
 * contract, not yet confirmed with the senior developer - flagged in the handover report.
 *
 * "A fake that is too forgiving hides bugs" (own house rule): these fakes are kept honest -
 * InMemoryConnectionProfileRepository really enforces name uniqueness on add() the same way a real
 * JSON-file repository would have to; FakeBrokerReachabilityChecker records which endpoints it was
 * actually asked to check, so a step definition can tell "the other endpoint is still reported as
 * reachable" apart from "the app never even checked it"; FakeCaCertificateValidator is not a canned
 * answer at all - it performs the same real java.security.cert.CertificateFactory parsing the real
 * `data-rabbit` implementation is expected to do, just without needing a dependency on data-rabbit's
 * concrete class from this module.
 */

/** In-memory stand-in for the real JSON-file-backed repository (`data-rabbit`, not built by this task's tests). */
class InMemoryConnectionProfileRepository : ConnectionProfileRepository {
    private val profilesByName = linkedMapOf<String, ConnectionProfile>()

    override fun list(): List<ConnectionProfile> = profilesByName.values.toList()

    override fun add(profile: ConnectionProfile): SaveConnectionResult {
        if (profilesByName.containsKey(profile.name)) {
            return SaveConnectionResult.NameAlreadyExists(profile.name)
        }
        profilesByName[profile.name] = profile
        return SaveConnectionResult.Saved(profile)
    }

    override fun update(profile: ConnectionProfile): SaveConnectionResult {
        check(profilesByName.containsKey(profile.name)) {
            "Cannot update connection '${profile.name}': no such profile exists in the repository"
        }
        profilesByName[profile.name] = profile
        return SaveConnectionResult.Saved(profile)
    }

    override fun delete(name: String) {
        profilesByName.remove(name)
    }
}

/**
 * In-memory stand-in for the real `java-keyring`-backed SecretStore. Deliberately NOT the real OS
 * keychain - that honesty is owned by M1-01's already-passing `KeyringRoundTripSpikeTest` and this
 * task's new `data-rabbit` test `KeyringSecretStoreTest` (real ConnectionProfile-keyed round trip), both run on demand via `:data-rabbit:keychainTest`.
 * This fake only has to prove the application *wires* passwords through SecretStore rather than
 * through ConnectionProfile/the repository, so it records every call for that assertion.
 */
class FakeSecretStore : SecretStore {
    private val passwordsByProfileName = mutableMapOf<String, String>()
    val storeCalls = mutableListOf<Pair<String, String>>()
    val deleteCalls = mutableListOf<String>()

    override fun store(profileName: String, password: String) {
        storeCalls += profileName to password
        passwordsByProfileName[profileName] = password
    }

    override fun retrieve(profileName: String): String? = passwordsByProfileName[profileName]

    override fun delete(profileName: String) {
        deleteCalls += profileName
        passwordsByProfileName.remove(profileName)
    }
}

/**
 * Test double for BrokerReachabilityChecker, configured per scenario to simulate the broker's state.
 * No Docker, no real socket/HTTP calls - that proof is the separate, real `@Tag("broker")` JUnit test
 * in `data-rabbit` (see its Design notes rationale). Records which endpoints were actually probed so
 * step definitions can tell a genuine "both probed, only one failed" apart from a checker that gives
 * up after the first failure.
 */
class FakeBrokerReachabilityChecker : BrokerReachabilityChecker {
    var streamReachable: Boolean = true
    var managementReachable: Boolean = true
    /** M2-14: which listeners the broker has. A TLS profile on a broker without TLS is refused; a plain profile on a broker without plain listeners cannot connect. */
    var tlsOffered: Boolean = true
    var plainOffered: Boolean = true
    var managementPluginEnabled: Boolean = true
    var certificateTrusted: Boolean = true

    /** Set by "the broker presents a certificate signed by a private CA" to require a matching CA path. */
    var requiredCaCertificatePath: String? = null

    val checkedEndpoints = mutableListOf<Endpoint>()

    /**
     * M2-14: one entry per attempt the app made to talk to the broker, true = with TLS. Never cleared, so a scenario can tell "refused
     * and left it at that" from "refused, then silently tried plain" (the app never falls back, in either direction).
     */
    val attempts = mutableListOf<Boolean>()

    override suspend fun check(profile: ConnectionProfile): ConnectionCheckResult {
        checkedEndpoints.clear()
        attempts += profile.tls

        if (profile.tls) {
            if (!tlsOffered) {
                return ConnectionCheckResult.TlsRefused
            }
            if (!certificateTrusted) {
                return ConnectionCheckResult.CertificateRejected
            }
            // Like the real checker: with TLS on a CA path that cannot be read is a failure; with TLS off the path is never read.
            profile.caCertificatePath?.let { path ->
                if (!java.io.File(path).isFile) {
                    return ConnectionCheckResult.EndpointUnreachable(Endpoint.STREAM, "could not load the CA certificate")
                }
            }
        } else if (!plainOffered) {
            return ConnectionCheckResult.EndpointUnreachable(Endpoint.STREAM, "connection closed")
        }

        val requiredCa = requiredCaCertificatePath
        if (profile.tls && requiredCa != null && profile.caCertificatePath != requiredCa) {
            checkedEndpoints += Endpoint.STREAM
            return ConnectionCheckResult.EndpointUnreachable(
                Endpoint.STREAM,
                "the broker's certificate is not trusted - configure the private CA certificate",
            )
        }

        // Both endpoints are always probed, unconditionally, before branching on which one (if any)
        // failed - a real checker cannot know the stream endpoint failed without having attempted the
        // management endpoint too (they are independent connections), so this fake must not
        // short-circuit after the first failure either. See "Check connection names the endpoint that
        // failed"'s "the other endpoint is still reported as reachable" assertion, which this exists
        // to make honest.
        checkedEndpoints += Endpoint.STREAM
        checkedEndpoints += Endpoint.MANAGEMENT

        if (!streamReachable) {
            return ConnectionCheckResult.EndpointUnreachable(Endpoint.STREAM, "connection refused")
        }
        if (!managementReachable) {
            return ConnectionCheckResult.EndpointUnreachable(Endpoint.MANAGEMENT, "connection refused")
        }

        if (!managementPluginEnabled) {
            return ConnectionCheckResult.ManagementPluginMissing("rabbitmq_stream_management")
        }

        return ConnectionCheckResult.BothReachable
    }
}

/**
 * NOT a mock: performs real X.509 parsing (java.security.cert.CertificateFactory), the same
 * operation the real `data-rabbit` CaCertificateValidator implementation is expected to do. Lives
 * here (a plain kotlin("jvm") module, not core's KMP commonMain, where java.security.cert is not
 * available) specifically so 01_connections.feature's step definitions do not need a dependency on
 * data-rabbit's concrete implementation class, only on core's CaCertificateValidator interface.
 */
class FakeCaCertificateValidator : CaCertificateValidator {
    /** M2-14: every path the app asked about, so "the CA is ignored when TLS is off" can show it never asked. */
    val validated = mutableListOf<String>()

    override fun validate(filePath: String): CaCertificateValidationResult {
        validated += filePath
        // M2-05 round 2, F1: same rule as the real validator - the field validates per keystroke, so a
        // directory, a missing or an unreadable file is a typed Invalid, never an exception.
        return try {
            val file = File(filePath)
            if (!file.isFile || !file.canRead()) {
                return CaCertificateValidationResult.Invalid("the file could not be read")
            }
            file.inputStream().use { input ->
                CertificateFactory.getInstance("X.509").generateCertificate(input)
            }
            CaCertificateValidationResult.Valid
        } catch (e: CertificateException) {
            CaCertificateValidationResult.Invalid("the file is not a valid certificate")
        } catch (e: java.io.IOException) {
            CaCertificateValidationResult.Invalid("the file could not be read")
        } catch (e: SecurityException) {
            CaCertificateValidationResult.Invalid("the file could not be read")
        }
    }
}

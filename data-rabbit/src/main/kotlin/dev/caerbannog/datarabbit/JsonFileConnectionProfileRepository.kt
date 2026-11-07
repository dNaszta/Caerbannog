package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileRepository
import dev.caerbannog.core.connections.DeclaredPermissions
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.TopologyControlLevel
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.File

/**
 * JSON-file-backed [ConnectionProfileRepository] (docs/tasks/M1-02-connection-profiles.md Scope: "a
 * JSON-file-backed implementation ... File location: ~/.caerbannog/connections.json", an engineering
 * call recorded in that task's Open points).
 *
 * Deliberately does NOT serialize `core`'s [ConnectionProfile] directly: a private `@Serializable`
 * [ConnectionProfileDto] lives here instead, so the kotlinx-serialization dependency/annotation never
 * has to touch `core`'s domain model (per the module-boundary rule that `core` holds pure
 * domain/contract code, free of any serialization-library import). The DTO mirrors
 * [ConnectionProfile] field for field; there is no password field to drop, since [ConnectionProfile]
 * never had one.
 *
 * [file] is a constructor parameter, not a hardcoded path, so tests can redirect it to a JUnit 5
 * `@TempDir` instead of touching a developer's real `~/.caerbannog/connections.json`; see
 * [defaultFile] for the real production path.
 */
@OptIn(ExperimentalSerializationApi::class)
class JsonFileConnectionProfileRepository(private val file: File) : ConnectionProfileRepository {

    @Serializable
    private data class ConnectionProfileDto(
        val name: String,
        val host: String,
        val virtualHost: String,
        val username: String,
        val streamPort: Int,
        val managementPort: Int,
        // A JsonElement, not a Boolean: any value that is not a JSON boolean (string, number, null, array, object) must
        // load as TLS on (the safe side) without failing the whole file. Always written, also when on.
        @EncodeDefault val tls: JsonElement = JsonPrimitive(true),
        val caCertificatePath: String? = null,
        val topologyControlLevel: TopologyControlLevelDto = TopologyControlLevelDto.READ_ONLY,
        // Optional and additive: no key is written when null (Json does not encode defaults), and old files
        // load as none. Json rejects unknown keys, so an older build cannot read a file that has this key:
        // downgrade is unsupported (local-only app).
        val declaredPermissions: DeclaredPermissionsDto? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val mqttPort: Int? = null,
    )

    @Serializable
    private data class DeclaredPermissionsDto(val configure: Boolean, val write: Boolean)

    @Serializable
    private enum class TopologyControlLevelDto {
        READ_ONLY,
        DECLARE_AND_EDIT,
        FULL_CONTROL,
    }

    private val json = Json { prettyPrint = true }

    override fun list(): List<ConnectionProfile> = readAll().map { it.toDomain() }

    override fun add(profile: ConnectionProfile): SaveConnectionResult {
        val current = readAll()
        if (current.any { it.name == profile.name }) {
            return SaveConnectionResult.NameAlreadyExists(profile.name)
        }
        writeAll(current + profile.toDto())
        return SaveConnectionResult.Saved(profile)
    }

    override fun update(profile: ConnectionProfile): SaveConnectionResult {
        val current = readAll()
        check(current.any { it.name == profile.name }) {
            "Cannot update connection '${profile.name}': no such profile exists in $file"
        }
        writeAll(current.map { if (it.name == profile.name) profile.toDto() else it })
        return SaveConnectionResult.Saved(profile)
    }

    override fun delete(name: String) {
        writeAll(readAll().filterNot { it.name == name })
    }

    private fun readAll(): List<ConnectionProfileDto> {
        if (!file.exists()) return emptyList()
        val text = file.readText()
        if (text.isBlank()) return emptyList()
        return json.decodeFromString(text)
    }

    private fun writeAll(profiles: List<ConnectionProfileDto>) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(profiles))
    }

    private fun ConnectionProfileDto.toDomain() = ConnectionProfile(
        name = name,
        host = host,
        virtualHost = virtualHost,
        username = username,
        streamPort = streamPort,
        managementPort = managementPort,
        tls = (tls as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: true,
        caCertificatePath = caCertificatePath,
        topologyControlLevel = when (topologyControlLevel) {
            TopologyControlLevelDto.READ_ONLY -> TopologyControlLevel.READ_ONLY
            TopologyControlLevelDto.DECLARE_AND_EDIT -> TopologyControlLevel.DECLARE_AND_EDIT
            TopologyControlLevelDto.FULL_CONTROL -> TopologyControlLevel.FULL_CONTROL
        },
        declaredPermissions = declaredPermissions?.let { DeclaredPermissions(configure = it.configure, write = it.write) },
        mqttPort = mqttPort,
    )

    private fun ConnectionProfile.toDto() = ConnectionProfileDto(
        name = name,
        host = host,
        virtualHost = virtualHost,
        username = username,
        streamPort = streamPort,
        managementPort = managementPort,
        tls = JsonPrimitive(tls),
        caCertificatePath = caCertificatePath,
        topologyControlLevel = when (topologyControlLevel) {
            TopologyControlLevel.READ_ONLY -> TopologyControlLevelDto.READ_ONLY
            TopologyControlLevel.DECLARE_AND_EDIT -> TopologyControlLevelDto.DECLARE_AND_EDIT
            TopologyControlLevel.FULL_CONTROL -> TopologyControlLevelDto.FULL_CONTROL
        },
        declaredPermissions = declaredPermissions?.let { DeclaredPermissionsDto(configure = it.configure, write = it.write) },
        mqttPort = mqttPort,
    )

    companion object {
        /** docs/tasks/M1-02-connection-profiles.md, Open points: the chosen real file location. */
        fun defaultFile(): File = File(System.getProperty("user.home"), ".caerbannog/connections.json")
    }
}

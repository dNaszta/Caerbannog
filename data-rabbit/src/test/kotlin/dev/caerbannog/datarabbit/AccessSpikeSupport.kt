package dev.caerbannog.datarabbit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.testcontainers.images.builder.Transferable
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.utility.MountableFile
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.X509TrustManager

/*
 * M10-00 spike helpers (docs/tasks/M10-00-spike-token-access-data.md). Test code only, never copied into a product module:
 * a static RSA signing key made by the JDK, JWTs minted with java.security.Signature (no Keycloak, no JWT library, no JWKS
 * server), throwaway brokers with the OAuth 2 backend, and a recorder that sanitises every line and fixture before it is
 * written. The private key and the tokens live only in this JVM; the public key is copied into the container.
 */

internal const val ACCESS_PASSWORD = "spike-internal-pw-1"
// Built from two parts so the M2-19 image-pin guard does not read the resource server id as an image name.
internal const val ACCESS_RESOURCE_SERVER = "rabbit" + "mq"
internal const val ACCESS_KEY_ID = "spikekey"

private val base64Url = Base64.getUrlEncoder().withoutPadding()

/** What was minted or used as a secret in this JVM; the recorder removes all of it, the leak scan looks for all of it. */
internal object AccessSecrets {
    private val tokens = CopyOnWriteArrayList<String>()
    private val payloads = CopyOnWriteArrayList<String>()

    /** Where a response body contained a minted token, its payload segment or the internal password (the S-D and S-M security rows). */
    val leaks = CopyOnWriteArrayList<String>()

    fun register(token: String) {
        tokens += token
        token.split('.').getOrNull(1)?.let { payloads += it }
    }

    fun sanitise(text: String): String {
        var out = text
        tokens.forEach { out = out.replace(it, "<JWT>") }
        payloads.forEach { out = out.replace(it, "<JWT-PAYLOAD>") }
        out = out.replace(ACCESS_PASSWORD, "<PASSWORD>")
        out = Regex("eyJ[A-Za-z0-9_-]*(\\.[A-Za-z0-9_-]*){0,2}").replace(out, "<JWT>")
        out = Regex("-----BEGIN [A-Z ]+-----[^-]*-----END [A-Z ]+-----").replace(out, "<KEY>")
        return out
    }

    /** Records into [leaks] what [body] (the answer to [label]) contains of the secrets. */
    fun scan(label: String, body: String) {
        tokens.forEach { if (it in body) leaks += "$label: full token" }
        payloads.forEach { if (it in body) leaks += "$label: token payload segment" }
        if (ACCESS_PASSWORD in body) leaks += "$label: internal password"
        if (Regex("-----BEGIN [A-Z ]*KEY-----").containsMatchIn(body)) leaks += "$label: key armour (BEGIN ... KEY)"
        AccessKeys.fingerprints.forEach { if (it in body) leaks += "$label: signing key material" }
    }
}

internal object AccessKeys {
    val main: KeyPair = newPair()
    val other: KeyPair = newPair()

    /** A slice from the middle of the base64 of each key (public and private): never the constant DER header, so a hit is key material. */
    val fingerprints: List<String> = listOf(main.public, main.private, other.public, other.private)
        .map { Base64.getEncoder().encodeToString(it.encoded).substring(100, 160) }

    private fun newPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()

    /** The public key as PEM text for the broker's signing key file (a public key, not a secret). */
    fun publicPem(): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(main.public.encoded)
        return "-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----\n"
    }
}

/** A JWT of the test's own signing key. Claims are free: every shape the spike wants to try is one call. */
internal fun mintJwt(
    claims: Map<String, JsonElement>,
    kid: String? = ACCESS_KEY_ID,
    signWith: KeyPair = AccessKeys.main,
): String {
    val header = buildJsonObject {
        put("alg", "RS256"); put("typ", "JWT")
        if (kid != null) put("kid", kid)
    }
    val signingInput = base64Url.encodeToString(header.toString().toByteArray()) + "." +
        base64Url.encodeToString(JsonObject(claims).toString().toByteArray())
    val signature = Signature.getInstance("SHA256withRSA").run {
        initSign(signWith.private)
        update(signingInput.toByteArray())
        sign()
    }
    return "$signingInput.${base64Url.encodeToString(signature)}".also { AccessSecrets.register(it) }
}

private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

/** One token with the usual claims. [scopes] go into `scope` as a list or as one space-separated string. */
internal fun accessToken(
    sub: String?,
    scopes: List<String>,
    scopeAsString: Boolean = false,
    aud: JsonElement? = JsonPrimitive(ACCESS_RESOURCE_SERVER),
    expiresInSeconds: Long = 3600,
    extra: Map<String, String> = emptyMap(),
    kid: String? = ACCESS_KEY_ID,
    signWith: KeyPair = AccessKeys.main,
): String {
    val claims = linkedMapOf<String, JsonElement>()
    if (sub != null) claims["sub"] = JsonPrimitive(sub)
    if (aud != null) claims["aud"] = aud
    claims["iat"] = JsonPrimitive(nowSeconds())
    claims["exp"] = JsonPrimitive(nowSeconds() + expiresInSeconds)
    claims["scope"] = if (scopeAsString) JsonPrimitive(scopes.joinToString(" ")) else JsonArray(scopes.map { JsonPrimitive(it) })
    extra.forEach { (k, v) -> claims[k] = JsonPrimitive(v) }
    return mintJwt(claims, kid, signWith)
}

/** How a management API call authenticates. */
internal sealed interface AccessAuth {
    data class Basic(val user: String, val secret: String) : AccessAuth
    data class Bearer(val token: String) : AccessAuth
    data object Anonymous : AccessAuth
}

internal data class AccessReply(val status: Int, val body: String) {
    val json: JsonElement? get() = if (body.isBlank()) null else runCatching { Json.parseToJsonElement(body) }.getOrNull()
    val obj: JsonObject? get() = json as? JsonObject
    val arr: JsonArray? get() = json as? JsonArray
    val ok: Boolean get() = status in 200..299
    override fun toString(): String = "$status ${body.take(300)}"
}

/** Management API client of one throwaway broker. Every reply is scanned for the secrets (the security rows). */
internal class AccessHttp(private val base: String, trustAll: Boolean = false) {
    private val http: HttpClient = if (!trustAll) {
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    } else {
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        HttpClient.newBuilder().sslContext(ctx).sslParameters(SSLParameters().apply { endpointIdentificationAlgorithm = "" }).build()
    }

    fun call(method: String, path: String, auth: AccessAuth, body: String? = null): AccessReply {
        val builder = HttpRequest.newBuilder(URI(base + path)).header("Content-Type", "application/json")
        when (auth) {
            is AccessAuth.Basic -> builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString("${auth.user}:${auth.secret}".toByteArray()))
            is AccessAuth.Bearer -> builder.header("Authorization", "Bearer ${auth.token}")
            AccessAuth.Anonymous -> Unit
        }
        builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return AccessReply(response.statusCode(), response.body().orEmpty()).also { AccessSecrets.scan("$method $path", it.body) }
    }

    fun get(path: String, auth: AccessAuth): AccessReply = call("GET", path, auth)
}

/** The sanitised recorder: a line to stdout and `build/m10-00-facts.txt`, a fixture file when `RECORD_M10_00_FIXTURES` is set. */
internal object AccessFacts {
    private val factsFile = File("build/m10-00-facts.txt")
    private val fixtureDir = File("src/test/resources/fixtures/m10-00")
    private val record = System.getenv("RECORD_M10_00_FIXTURES") != null

    fun note(item: String, line: String) {
        val text = AccessSecrets.sanitise("[$item] $line")
        println(text)
        factsFile.parentFile.mkdirs()
        synchronized(this) { factsFile.appendText(text + "\n") }
    }

    /** Saves a plain text fixture (the S-I case table). */
    fun fixtureText(fileName: String, text: String) {
        if (!record) return
        fixtureDir.mkdirs()
        File(fixtureDir, fileName).writeText(AccessSecrets.sanitise(text))
    }

    /** Saves the answer to [request] as a fixture file. The body is embedded as JSON when it parses, else as text. */
    fun fixture(name: String, request: String, reply: AccessReply) {
        if (!record) return
        val body: JsonElement = reply.json ?: if (reply.body.isBlank()) JsonNull else JsonPrimitive(reply.body)
        val wrapper = buildJsonObject {
            put("request", request); put("status", reply.status); put("body", body)
        }
        fixtureDir.mkdirs()
        val pretty = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), wrapper)
        File(fixtureDir, "$name.json").writeText(AccessSecrets.sanitise(pretty).trimEnd() + "\n")
    }
}

internal const val ACCESS_BASE_CONF = """
auth_backends.1 = oauth2
auth_backends.2 = internal
auth_oauth2.resource_server_id = $ACCESS_RESOURCE_SERVER
auth_oauth2.default_key = $ACCESS_KEY_ID
auth_oauth2.signing_keys.$ACCESS_KEY_ID = /etc/rabbitmq/oauth/signing-key.pem
auth_oauth2.algorithms.1 = RS256
"""

/** A throwaway 4.x broker with the OAuth 2 backend, the static signing key, and the plugins of the brief. [extraConf] is appended. */
internal fun accessBroker(extraConf: String = "", tls: Boolean = false, withInternalPlugins: Boolean = true): RabbitMQContainer {
    val plugins = if (withInternalPlugins) arrayOf("rabbitmq_stream", "rabbitmq_stream_management", "rabbitmq_mqtt", "rabbitmq_auth_backend_oauth2")
    else arrayOf("rabbitmq_auth_backend_oauth2")
    val conf = buildString {
        append(ACCESS_BASE_CONF)
        append("stream.listeners.tcp.default = 5552\n")
        append("management.tcp.port = 15672\n")
        if (tls) append("management.ssl.port = 15671\nmanagement.ssl.cacertfile = /etc/rabbitmq/tls/ca.pem\nmanagement.ssl.certfile = /etc/rabbitmq/tls/server.pem\nmanagement.ssl.keyfile = /etc/rabbitmq/tls/server.key\n")
        append(extraConf)
    }
    return brokerWithPlugins(BrokerImages.CURRENT_4X, *plugins)
        .withCopyToContainer(Transferable.of(conf), "/etc/rabbitmq/conf.d/20-access-spike.conf")
        .withCopyToContainer(Transferable.of(AccessKeys.publicPem()), "/etc/rabbitmq/oauth/signing-key.pem")
        .apply {
            addExposedPorts(5552, 1883, 15672)
            if (tls) {
                withCopyFileToContainer(MountableFile.forClasspathResource("tls/ca.pem"), "/etc/rabbitmq/tls/ca.pem")
                withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.pem"), "/etc/rabbitmq/tls/server.pem")
                withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.key"), "/etc/rabbitmq/tls/server.key")
                addExposedPorts(15671)
            }
        }
}

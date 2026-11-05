package dev.caerbannog.devseed

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import javax.net.ssl.SSLParameters

/** The few management API calls the seed needs, over HTTPS (HTTP with --no-tls) with the JDK client (no new library). */
internal class ManagementClient(private val args: SeedArgs) : AutoCloseable {
    private val http: HttpClient = HttpClient.newBuilder().apply {
        if (args.tls) {
            sslContext(Tls.sslContext(args.caPath))
            sslParameters(SSLParameters().apply { endpointIdentificationAlgorithm = "" })
        }
    }.connectTimeout(Duration.ofSeconds(10)).build()

    // Built per call, never stored in a log line or an exception message.
    private fun authorization(): String =
        "Basic " + Base64.getEncoder().encodeToString("${args.user}:${args.password}".toByteArray(Charsets.UTF_8))

    private fun url(path: String) = URI.create("${if (args.tls) "https" else "http"}://${args.host}:${args.managementPort}/api/$path")

    /** `POST /api/definitions`: the import is idempotent for equal declarations. */
    fun importDefinitions(body: String) {
        send("POST", "definitions", body, Duration.ofMinutes(5))
    }

    /** A policy that sets stream `max-age` on the named streams; replaces the one of an earlier run. */
    fun setRetentionPolicy(vhost: String, streams: List<String>, maxAge: String) {
        val pattern = streams.joinToString("|", prefix = "^(", postfix = ")$") { Regex.escape(it) }
        val body = "{\"pattern\":\"${pattern.replace("\\", "\\\\").replace("\"", "\\\"")}\"," +
            "\"apply-to\":\"streams\",\"priority\":10,\"definition\":{\"max-age\":\"$maxAge\"}}"
        send("PUT", "policies/${encode(vhost)}/dev-seed-retention", body, Duration.ofSeconds(30))
    }

    private fun send(method: String, path: String, body: String, timeout: Duration) {
        val request = HttpRequest.newBuilder(url(path))
            .timeout(timeout)
            .header("Authorization", authorization())
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            // The broker's own text stays visible (it never contains the password).
            throw SeedFailure("$method /api/$path answered ${response.statusCode()}: ${response.body()}")
        }
    }

    override fun close() = http.close()

    private fun encode(vhost: String) = URLEncoder.encode(vhost, Charsets.UTF_8).replace("+", "%20")
}

/** A failure the tool reports in plain words and exits with status 1. */
internal class SeedFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

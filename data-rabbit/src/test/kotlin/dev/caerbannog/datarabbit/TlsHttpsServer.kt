package dev.caerbannog.datarabbit

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** A local HTTPS "management API" with a throw-away self-signed certificate (keytool of the running JDK), answering 200 on every path. */
internal class TlsHttpsServer(dir: Path, commonName: String = "localhost") : AutoCloseable {
    private val keystore = File(dir.toFile(), "server.p12")
    val certificatePath: String = File(dir.toFile(), "server.pem").path
    private val server: HttpsServer

    init {
        keytool("-genkeypair", "-keyalg", "RSA", "-keysize", "2048", "-alias", "srv", "-dname", "CN=$commonName", "-ext", "san=dns:$commonName",
            "-validity", "2", "-keystore", keystore.path, "-storetype", "PKCS12", "-storepass", "changeit")
        keytool("-exportcert", "-rfc", "-alias", "srv", "-keystore", keystore.path, "-storepass", "changeit", "-file", certificatePath)
        val ks = KeyStore.getInstance("PKCS12").apply { keystore.inputStream().use { load(it, "changeit".toCharArray()) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "changeit".toCharArray()) }
        val context = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        server = HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 10)
        server.httpsConfigurator = HttpsConfigurator(context)
        server.createContext("/") { exchange ->
            val body = """{"name":"svc","tags":[]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    val port: Int get() = server.address.port

    override fun close() = server.stop(0)

    private fun keytool(vararg args: String) {
        val exe = File(System.getProperty("java.home"), "bin/keytool").path
        val p = ProcessBuilder(listOf(exe) + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) { "keytool failed: $out" }
    }
}

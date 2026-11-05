package dev.caerbannog.datarabbit

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * M2-14a test tooling (no Docker): a plain HTTP "management API" and a plain TCP "stream port" on localhost, both recording what the
 * app sent. They are honest in the one way that matters here: they speak NO TLS, so a TLS-on client can only fail its handshake, and
 * they record the bytes, so "nothing is sent" is a fact and not a guess.
 */
internal class PlainManagementServer(private val routes: Map<String, Pair<Int, String>> = emptyMap()) : AutoCloseable {

    data class Seen(val method: String, val rawPath: String, val authorization: String?)

    private val requests = CopyOnWriteArrayList<Seen>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            requests += Seen(exchange.requestMethod, exchange.requestURI.rawPath, exchange.requestHeaders.getFirst("Authorization"))
            // The longest registered prefix wins; anything unregistered is a 404 like a missing route.
            val (status, body) = routes.entries.filter { exchange.requestURI.rawPath.startsWith(it.key) }.maxByOrNull { it.key.length }?.value
                ?: (404 to """{"error":"Object Not Found","reason":"Not Found"}""")
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        start()
    }

    val port: Int get() = server.address.port
    val seen: List<Seen> get() = requests.toList()

    override fun close() = server.stop(0)
}

/** Accepts connections, records the first bytes of each (up to [limit]), then closes the socket. Speaks nothing back. */
internal class RecordingSocketServer(private val limit: Int = 64) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val received = Collections.synchronizedList(ArrayList<ByteArray>())
    private val acceptor = Thread {
        try {
            while (true) {
                val socket = server.accept()
                socket.use {
                    it.soTimeout = 3_000
                    val buffer = ByteArray(limit)
                    try {
                        // One read is enough: a client sends its first frame or ClientHello in one go. Recorded at once, before the drain.
                        val n = it.getInputStream().read(buffer)
                        received += if (n > 0) buffer.copyOf(n) else ByteArray(0)
                        // Then drain what is still in flight and close with a clean FIN (not a reset), like a server that gave up on garbage.
                        it.soTimeout = 200
                        while (it.getInputStream().read(ByteArray(4096)) >= 0) { /* drain */ }
                    } catch (_: java.io.IOException) {
                        // a client that said nothing, or went quiet: close now
                    }
                }
            }
        } catch (_: java.io.IOException) {
            // closed
        }
    }.apply { isDaemon = true; start() }

    val port: Int get() = server.localPort

    /** The first bytes of every connection so far, in order. */
    val firstBytes: List<ByteArray> get() = synchronized(received) { received.toList() }

    override fun close() {
        server.close()
        acceptor.join(5_000)
    }
}

internal fun closedLocalPort(): Int = ServerSocket(0).use { it.localPort }

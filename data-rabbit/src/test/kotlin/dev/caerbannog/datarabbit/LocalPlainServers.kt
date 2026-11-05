package dev.caerbannog.datarabbit

import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * M2-14a test tooling (no Docker): a plain HTTP "management API" and a plain TCP "stream port" on localhost, both recording what the
 * app sent. They are honest in the one way that matters here: they speak NO TLS, so a TLS-on client can only fail its handshake, and
 * they record the bytes, so "nothing is sent" is a fact and not a guess.
 */
internal class PlainManagementServer(
    private val routes: Map<String, Pair<Int, String>> = emptyMap(),
    /**
     * M2-14c: answer bytes that are not an HTTP request (a TLS ClientHello) with a `400 Bad Request` and close, like the real broker's
     * Cowboy does (measured: the JDK client then reports `SSLException: Unrecognized SSL message, plaintext connection?`). Off by default:
     * the older tests rely on the silent close.
     */
    private val answerGarbageWith400: Boolean = false,
) : AutoCloseable {

    data class Seen(val method: String, val rawPath: String, val authorization: String?)

    private val requests = CopyOnWriteArrayList<Seen>()
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    // A hand-rolled HTTP/1.1 server, not the JDK HttpServer: the JDK one stays silent on bytes that are not an HTTP request line,
    // so a TLS ClientHello ran into the client's request timeout. A real broker (Cowboy) answers 400 and closes, which a TLS client
    // sees as a failed handshake. This one closes at once on anything that is not "METHOD /path HTTP/1.x".
    private val acceptor = Thread {
        try {
            while (true) {
                val socket = server.accept()
                Thread { serve(socket) }.apply { isDaemon = true; start() }
            }
        } catch (_: java.io.IOException) {
            // closed
        }
    }.apply { isDaemon = true; start() }

    private fun serve(socket: java.net.Socket) { socket.use {
        try {
            it.soTimeout = 3_000
            val raw = java.io.BufferedInputStream(it.getInputStream())
            if (answerGarbageWith400) {
                raw.mark(16)
                val head = ByteArray(8)
                val n = raw.read(head)
                raw.reset()
                if (n > 0 && head[0].toInt().toChar() !in 'A'..'Z') {
                    it.getOutputStream().apply { write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray()); flush() }
                    return@use
                }
            }
            val input = raw.bufferedReader(Charsets.ISO_8859_1)
            val requestLine = input.readLine() ?: return@use
            val parts = requestLine.split(' ')
            if (parts.size != 3 || !parts[2].startsWith("HTTP/1.") || !parts[1].startsWith("/") || !parts[0].all { c -> c in 'A'..'Z' }) return@use
            var authorization: String? = null
            while (true) {
                val line = input.readLine() ?: return@use
                if (line.isEmpty()) break
                if (line.startsWith("Authorization:", ignoreCase = true)) authorization = line.substringAfter(':').trim()
            }
            val rawPath = parts[1].substringBefore('?')
            requests += Seen(parts[0], rawPath, authorization)
            // The longest registered prefix wins; anything unregistered is a 404 like a missing route.
            val (status, body) = routes.entries.filter { e -> rawPath.startsWith(e.key) }.maxByOrNull { e -> e.key.length }?.value
                ?: (404 to """{"error":"Object Not Found","reason":"Not Found"}""")
            val bytes = body.toByteArray()
            val head = "HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            it.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); write(bytes); flush() }
        } catch (_: java.io.IOException) {
            // client went away or said nothing
        }
    } }

    val port: Int get() = server.localPort
    val seen: List<Seen> get() = requests.toList()

    override fun close() {
        server.close()
        acceptor.join(5_000)
    }
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

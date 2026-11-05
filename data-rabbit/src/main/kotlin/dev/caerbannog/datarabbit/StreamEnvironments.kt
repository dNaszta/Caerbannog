package dev.caerbannog.datarabbit

import com.rabbitmq.stream.Address
import com.rabbitmq.stream.Environment
import dev.caerbannog.core.connections.ConnectionProfile
import io.netty.channel.ChannelOption
import io.netty.handler.ssl.SslContextBuilder
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.TrustManagerFactory

/**
 * The stream client side of one held connection: one `Environment` per vhost, built on the first offset call of
 * that vhost (a stream connection is bound to one vhost) and closed with the holder. Only `queryStreamStats` is
 * ever called: no consumer, no publisher, nothing that writes.
 *
 * TLS: the trust is the profile CA from [TlsSupport] and nothing else. The host name is NOT checked (decision D7,
 * same as the management client): Netty 4.2 would check it by default, so the algorithm is cleared explicitly.
 * Timeouts: [ConnectionTimeouts].
 *
 * Closing never blocks: an environment still being built is closed by whoever finishes building it.
 */
internal class StreamEnvironments(
    private val profile: ConnectionProfile,
    password: String,
    private val transport: BrokerTransport,
    // Null builds the real environment; a test passes a failing builder.
    private val environmentFactory: ((vhost: String) -> Environment)? = null,
) : FirstOffsetSource, AutoCloseable {

    @Volatile
    private var password: String? = password
    private val closed = AtomicBoolean(false)
    private val environments = ConcurrentHashMap<String, CompletableFuture<Environment>>()

    override fun read(vhost: String, stream: String): Long =
        environmentFor(vhost).queryStreamStats(stream).firstOffset()

    private fun environmentFor(vhost: String): Environment {
        check(!closed.get()) { "closed" }
        val mine = CompletableFuture<Environment>()
        val existing = environments.putIfAbsent(vhost, mine)
        if (existing != null) return await(existing)
        try {
            val environment = environmentFactory?.invoke(vhost) ?: build(vhost)
            mine.complete(environment)
            if (closed.get()) environment.close()
            return environment
        } catch (e: Exception) {
            // Not cached: the next call tries again (the broker may have been down for a moment).
            environments.remove(vhost, mine)
            mine.completeExceptionally(e)
            throw e
        }
    }

    // join() wraps a failed build in a CompletionException; callers (and the error mapper) need the real class.
    private fun await(future: CompletableFuture<Environment>): Environment = try {
        future.join()
    } catch (e: CompletionException) {
        throw e.cause ?: e
    }

    private fun build(vhost: String): Environment {
        val secret = checkNotNull(password) { "closed" }
        val trust = (transport as BrokerTransport.Tls).trust // M2-14 stub: plain is not built yet
        val sslContext = SslContextBuilder.forClient()
            .trustManager(trust)
            .endpointIdentificationAlgorithm(null)
            .build()
        val address = Address(profile.host, profile.streamPort)
        return Environment.builder()
            .host(profile.host)
            .port(profile.streamPort)
            .username(profile.username)
            .password(secret)
            .virtualHost(vhost)
            .addressResolver { address }
            .rpcTimeout(Duration.ofSeconds(ConnectionTimeouts.STREAM_RPC_SECONDS))
            .netty().bootstrapCustomizer { it.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, ConnectionTimeouts.STREAM_CONNECT_MILLIS) }
            .environmentBuilder()
            .tls().sslContext(sslContext)
            .environmentBuilder()
            .build()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        password = null
        environments.values.forEach { future -> future.thenAccept { it.close() } }
    }
}

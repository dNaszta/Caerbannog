package dev.caerbannog.devseed

import com.rabbitmq.stream.Address
import com.rabbitmq.stream.Environment
import io.netty.handler.ssl.SslContextBuilder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Publishes the generated messages into streams with the stream client over TLS (appends on every run). */
internal class StreamPublisher(private val args: SeedArgs) : AutoCloseable {
    private val environment: Environment = build()

    private fun build(): Environment {
        val address = Address(args.host, args.streamPort)
        val builder = Environment.builder()
            .host(args.host)
            .port(args.streamPort)
            .username(args.user)
            .password(args.password)
            .virtualHost(args.vhost)
            .addressResolver { address }
        if (!args.tls) return builder.build()
        val sslContext = SslContextBuilder.forClient()
            .trustManager(Tls.trustManagerFactory(args.caPath))
            .endpointIdentificationAlgorithm(null)
            .build()
        return builder.tls().sslContext(sslContext).environmentBuilder().build()
    }

    /** Creates the stream when it is missing (an existing one keeps the arguments from the definitions). */
    fun ensureStream(stream: String) {
        if (!environment.streamExists(stream)) environment.streamCreator().stream(stream).create()
    }

    /** Sends [count] messages and waits until the broker has confirmed all of them; returns the confirmed count. */
    fun publish(stream: String, count: Int): Int {
        if (count == 0) return 0
        val confirmed = CountDownLatch(count)
        environment.producerBuilder().stream(stream).build().use { producer ->
            MessageGenerator.messages(stream, count).forEach { body ->
                producer.send(producer.messageBuilder().addData(body).build()) { status ->
                    if (status.isConfirmed) confirmed.countDown()
                }
            }
            if (!confirmed.await(60, TimeUnit.SECONDS)) {
                throw SeedFailure("Stream $stream: only ${count - confirmed.count.toInt()} of $count messages were confirmed in 60 s")
            }
        }
        return count
    }

    override fun close() = environment.close()
}

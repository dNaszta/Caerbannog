package dev.caerbannog.datarabbit

/**
 * Timeouts of one held connection, named in one place so the product owner can change them (M2-03 brief: 3 s
 * connect, 5 s RPC; one failed stream attempt can take about twice the connect value). Netty alone would wait 30 s.
 */
internal object ConnectionTimeouts {
    const val MANAGEMENT_MILLIS: Long = 5_000
    const val STREAM_CONNECT_MILLIS: Int = 3_000
    const val STREAM_RPC_SECONDS: Long = 5
}

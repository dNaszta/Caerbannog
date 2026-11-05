package dev.caerbannog.devseed

/**
 * M2-12 skeleton: signatures only, so the tests in src/test compile and fail (NotImplementedError). The senior
 * developer replaces the bodies; the tests are the contract (see SeedArgsTest, MessageGeneratorTest,
 * QueueNamesTest, ManyQueuesDefinitionsTest).
 */
class SeedArgsException(message: String) : IllegalArgumentException(message)

data class SeedArgs(
    val host: String,
    val streamPort: Int,
    val managementPort: Int,
    val user: String,
    val password: String,
    val caPath: String?,
    val vhost: String,
    val streams: List<String>,
    val messages: Int,
    val queues: Int,
    val retentionMaxAge: String?,
) {
    companion object {
        fun parse(args: List<String>): SeedArgs = TODO("M2-12: senior developer")
    }
}

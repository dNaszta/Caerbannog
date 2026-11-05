package dev.caerbannog.devseed

/** A bad command line; the message names the offending flag and never contains a flag's value that is a secret. */
class SeedArgsException(message: String) : IllegalArgumentException(message)

/**
 * The parsed command line of the seed tool. `--password` is the only required flag (a default password in code
 * would be a secret); `--queues` has no default, so no queue is generated without it.
 * [toString] leaves the password out: the tool must never log it (M2-12 C8).
 */
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
    /** False with `--no-tls`: plain stream protocol and plain HTTP management, [caPath] is then always null. */
    val tls: Boolean = true,
    /** M2-06-F-A stub: `--hold` keeps a publisher and a consumer per stream connected until Ctrl+C (no value). */
    val hold: Boolean = false,
) {
    override fun toString(): String =
        "SeedArgs(host=$host, streamPort=$streamPort, managementPort=$managementPort, user=$user, " +
            "tls=$tls, caPath=$caPath, vhost=$vhost, streams=$streams, messages=$messages, queues=$queues, " +
            "retentionMaxAge=$retentionMaxAge)"

    companion object {
        val DEFAULT_STREAMS: List<String> = listOf("dev-orders", "dev-events", "dev-audit")

        private val MAX_AGE = Regex("[0-9]+[YMDhms]")

        /** Parses `--name value` pairs in any order; later occurrences of a flag win. */
        fun parse(args: List<String>): SeedArgs {
            val values = mutableMapOf<String, String>()
            var tls = true
            var i = 0
            while (i < args.size) {
                val flag = args[i]
                if (flag == NO_TLS) {
                    // The only switch without a value; a stray token after it is read as the next flag.
                    tls = false
                    i += 1
                    continue
                }
                if (flag !in FLAGS) throw SeedArgsException("Unknown flag: $flag")
                if (i + 1 >= args.size) throw SeedArgsException("Flag $flag needs a value")
                values[flag] = args[i + 1]
                i += 2
            }
            val password = values["--password"]
            if (password.isNullOrEmpty()) throw SeedArgsException("Flag --password is required")
            val queues = count(values, "--queues", 0)
            val vhost = values["--vhost"] ?: "/"
            if ("--max-age" in values && queues > 0 && vhost != "/") {
                throw SeedArgsException(
                    "Flag --max-age cannot be combined with --queues N on vhost '$vhost': a --queues run on another " +
                        "vhost only declares queues and would ignore --max-age. Run them separately.",
                )
            }
            return SeedArgs(
                host = values["--host"] ?: "localhost",
                streamPort = port(values, "--stream-port", if (tls) 5551 else 5552),
                managementPort = port(values, "--management-port", if (tls) 15671 else 15672),
                user = values["--user"] ?: "admin",
                password = password,
                caPath = if (tls) values["--ca"] else null,
                tls = tls,
                vhost = vhost,
                streams = values["--streams"]?.let(::streamNames) ?: DEFAULT_STREAMS,
                messages = count(values, "--messages", 1000),
                queues = queues,
                retentionMaxAge = values["--max-age"]?.also {
                    if (!MAX_AGE.matches(it)) {
                        throw SeedArgsException("Flag --max-age takes a number and a unit Y, M, D, h, m or s, such as 7D")
                    }
                },
            )
        }

        private const val NO_TLS = "--no-tls"

        private val FLAGS = setOf(
            "--host", "--stream-port", "--management-port", "--user", "--password", "--ca", "--vhost",
            "--streams", "--messages", "--queues", "--max-age",
        )

        private fun number(values: Map<String, String>, flag: String, default: Int): Int {
            val raw = values[flag] ?: return default
            return raw.trim().toIntOrNull() ?: throw SeedArgsException("Flag $flag needs a whole number, got '$raw'")
        }

        private fun port(values: Map<String, String>, flag: String, default: Int): Int =
            number(values, flag, default).also {
                if (it !in 1..65535) throw SeedArgsException("Flag $flag needs a port from 1 to 65535")
            }

        private fun count(values: Map<String, String>, flag: String, default: Int): Int =
            number(values, flag, default).also {
                if (it < 0) throw SeedArgsException("Flag $flag must not be negative")
            }

        private fun streamNames(raw: String): List<String> {
            val names = raw.split(',').map { it.trim() }
            if (names.any { it.isEmpty() }) throw SeedArgsException("Flag --streams needs a comma separated list of non-empty names")
            return names
        }
    }
}

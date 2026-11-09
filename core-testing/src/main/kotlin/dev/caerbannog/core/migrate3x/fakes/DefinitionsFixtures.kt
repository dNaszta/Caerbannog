package dev.caerbannog.core.migrate3x.fakes

/**
 * M8-01: reads the recorded definitions exports of `core-testing/src/main/resources/fixtures/m8-01/` (see its README: password hashes
 * are sentinels, the shovel file is hand-built). One copy for `core` jvmTest and `acceptance-tests`.
 */
object DefinitionsFixtures {
    const val FULL: String = "definitions-3.13-full.json"
    const val VHOST_V1: String = "definitions-3.13-vhost-v1.json"
    const val SHOVEL_PARAMETER: String = "definitions-3.13-shovel-parameter.json"

    fun read(name: String): String {
        val path = "fixtures/m8-01/$name"
        val stream = DefinitionsFixtures::class.java.classLoader.getResourceAsStream(path) ?: error("missing fixture $path")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}

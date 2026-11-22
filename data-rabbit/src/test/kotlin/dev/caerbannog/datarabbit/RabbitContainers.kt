package dev.caerbannog.datarabbit

import org.testcontainers.images.builder.Transferable
import org.testcontainers.rabbitmq.RabbitMQContainer

/**
 * The single place that builds a broker container for the live tests. Testcontainers 2.x has no plugin API on
 * `RabbitMQContainer`, so the plugins are enabled the way the image documents it: the `enabled_plugins` file is
 * copied to `/etc/rabbitmq/enabled_plugins` before the broker starts. The file replaces the image's own, so it
 * lists `rabbitmq_management` too (the `-management` images ship it; every test talks to the management API).
 */
private const val ENABLED_PLUGINS_PATH = "/etc/rabbitmq/enabled_plugins"

/** An Erlang term file for `enabled_plugins`: `[rabbitmq_management,rabbitmq_stream].` */
internal fun enabledPluginsFile(vararg plugins: String): String =
    (listOf("rabbitmq_management") + plugins).distinct().joinToString(prefix = "[", postfix = "].\n", separator = ",")

/** A [RabbitMQContainer] of [image] that starts with [plugins] enabled (plus the management plugin). */
internal fun brokerWithPlugins(image: String, vararg plugins: String): RabbitMQContainer =
    RabbitMQContainer(image).withCopyToContainer(Transferable.of(enabledPluginsFile(*plugins)), ENABLED_PLUGINS_PATH)

/**
 * The one place that names a RabbitMQ image for the live tests (M2-19). A floating tag let a new RabbitMQ release turn
 * `main` red without a commit; every tag here is an explicit patch version, and moving to a newer one is the deliberate
 * procedure in `dev/README.md` ("Image versions"). A tag can be re-pushed by the registry; pinning by digest is out of scope.
 * The two dev compose files and the Risks line in `docs/spec.md` carry the same versions.
 */
internal object BrokerImages {
    /** The 3.x broker: the read-only migration source (M8). */
    const val LEGACY_3X = "rabbitmq:3.13.7-management"

    /** The upgrade ladder of the M8 spikes, one step per minor version; the 4.4 step is [CURRENT_4X]. */
    const val UPGRADE_4_0 = "rabbitmq:4.0.9-management"
    const val UPGRADE_4_1 = "rabbitmq:4.1.8-management"
    const val UPGRADE_4_2 = "rabbitmq:4.2.9-management"
    const val UPGRADE_4_3 = "rabbitmq:4.3.8-management"

    /**
     * The 4.x broker of every product live test. Only the non-blocking CI job on the floating tag overrides it, through
     * `-PbrokerImage=...` (the `brokerTest` task sets `caerbannog.brokerImage`); nothing else reads that property.
     */
    val CURRENT_4X: String = System.getProperty("caerbannog.brokerImage")?.takeIf { it.isNotBlank() } ?: "rabbitmq:4.4.1-management"
}

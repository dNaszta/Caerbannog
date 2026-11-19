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

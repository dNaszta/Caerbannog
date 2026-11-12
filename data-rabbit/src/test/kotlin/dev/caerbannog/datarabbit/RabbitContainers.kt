package dev.caerbannog.datarabbit

import com.github.dockerjava.api.command.InspectContainerResponse
import org.testcontainers.containers.ContainerLaunchException
import org.testcontainers.containers.RabbitMQContainer

/**
 * A broker container that enables the given plugins once it is up, with the same command
 * (`rabbitmq-plugins enable ...`) that Testcontainers' `withPluginsEnabled` runs. That method
 * is deprecated in Java in the pinned version, its replacement (`org.testcontainers.rabbitmq`)
 * has no plugin API, and the deprecation note points at `execInContainer`, which is what this does.
 */
private class PluginRabbitMQContainer(image: String, private val plugins: List<String>) : RabbitMQContainer(image) {
    override fun containerIsStarted(containerInfo: InspectContainerResponse) {
        super.containerIsStarted(containerInfo)
        if (plugins.isEmpty()) return
        val result = execInContainer("rabbitmq-plugins", "enable", *plugins.toTypedArray())
        if (result.exitCode != 0) {
            throw ContainerLaunchException("rabbitmq-plugins enable ${plugins.joinToString(" ")} failed: ${result.stderr}")
        }
    }
}

/** A [RabbitMQContainer] of [image] with [plugins] enabled after start. */
internal fun brokerWithPlugins(image: String, vararg plugins: String): RabbitMQContainer =
    PluginRabbitMQContainer(image, plugins.toList())

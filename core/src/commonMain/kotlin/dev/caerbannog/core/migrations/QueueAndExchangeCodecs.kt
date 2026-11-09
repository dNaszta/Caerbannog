package dev.caerbannog.core.migrations

internal val declareQueueCodec = codec<MigrationAction.DeclareQueue>(
    name = "declareQueue",
    read = { entry ->
        val name = entry.text("name")
        val arguments = entry.typedEntries("arguments")
        when {
            name.isNullOrBlank() -> refused("queue name is required")
            arguments == null -> refused(ARGUMENTS_INVALID)
            "x-queue-type" in arguments -> refused(QUEUE_TYPE_IN_ARGUMENTS)
            else -> read(
                MigrationAction.DeclareQueue(
                    name = name,
                    type = entry.text("type") ?: "quorum",
                    vhost = entry.vhost(),
                    durable = entry.flag("durable", true),
                    arguments = arguments,
                    autoDelete = entry.flag("autoDelete", false),
                ),
            )
        }
    },
    write = { action, out ->
        out.putText("name", action.name)
        out.putText("type", action.type)
        out.putFlagUnlessDefault("durable", action.durable, default = true)
        out.putFlagUnlessDefault("autoDelete", action.autoDelete, default = false)
        out.putTyped("arguments", action.arguments)
    },
)

internal val declareExchangeCodec = codec<MigrationAction.DeclareExchange>(
    name = "declareExchange",
    read = { entry ->
        val name = entry.text("name")
        val arguments = entry.typedEntries("arguments")
        when {
            name.isNullOrBlank() -> refused("exchange name is required")
            arguments == null -> refused(ARGUMENTS_INVALID)
            else -> read(
                MigrationAction.DeclareExchange(
                    name = name,
                    type = entry.text("type") ?: "direct",
                    vhost = entry.vhost(),
                    durable = entry.flag("durable", true),
                    autoDelete = entry.flag("autoDelete", false),
                    internal = entry.flag("internal", false),
                    arguments = arguments,
                ),
            )
        }
    },
    write = { action, out ->
        out.putText("name", action.name)
        out.putText("type", action.type)
        out.putFlagUnlessDefault("durable", action.durable, default = true)
        out.putFlagUnlessDefault("autoDelete", action.autoDelete, default = false)
        out.putFlagUnlessDefault("internal", action.internal, default = false)
        out.putTyped("arguments", action.arguments)
    },
)

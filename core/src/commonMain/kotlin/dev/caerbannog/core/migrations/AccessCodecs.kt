package dev.caerbannog.core.migrations

/** M8-12a STUB: the codecs of the four access actions. They read nothing (a refusal) and write nothing until built. */
internal val declareVhostCodec = codec<MigrationAction.DeclareVhost>("declareVhost", { refused("M8-12a STUB") }, { _, _ -> }) // M8-12a STUB
internal val declareUserCodec = codec<MigrationAction.DeclareUser>("declareUser", { refused("M8-12a STUB") }, { _, _ -> }) // M8-12a STUB
internal val setPermissionsCodec = codec<MigrationAction.SetPermissions>("setPermissions", { refused("M8-12a STUB") }, { _, _ -> }) // M8-12a STUB
internal val setTopicPermissionsCodec = codec<MigrationAction.SetTopicPermissions>("setTopicPermissions", { refused("M8-12a STUB") }, { _, _ -> }) // M8-12a STUB

/** M8-12a STUB: the raw file text with every `passwordHash` value replaced by `hash hidden` (D4); the file itself and its checksum keep the real value. */
fun redactedRaw(rawJson: String): String = rawJson // M8-12a STUB

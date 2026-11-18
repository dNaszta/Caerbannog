package dev.caerbannog.core.migrations

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder

/** Outcome of reading one entry of the `up` list. A refusal carries a fixed message that never echoes the input. */
internal sealed interface ActionReadResult {
    data class Read(val action: MigrationAction) : ActionReadResult
    data class Refused(val reason: String) : ActionReadResult
}

/**
 * Reads and writes one kind of migration action. One codec per action keeps [MigrationParser] free of a growing `when`:
 * a new action is a new codec in [ActionCodecs], nothing else.
 */
internal interface ActionCodec {
    /** The value of the `action` key. */
    val name: String

    /**
     * The keys [read] looks at, besides `action` and `vhost`, which every action has. [MigrationParser.hasUnknownKeys] relies on it: a key that is not
     * listed here is dropped by a save, so a file holding one must not be opened as steps. A guard test proves the list matches [read].
     */
    val keys: Set<String>

    /** Reads the keys of [entry] (the `action` key is already known to be [name]). */
    fun read(entry: JsonObject): ActionReadResult

    /** Writes the keys after `action` and `vhost`; ignores an action of another kind. */
    fun write(action: MigrationAction, out: JsonObjectBuilder)
}

/** Builds a codec for the action type [A]; [write] is only called for an [A], so no cast is needed. */
internal inline fun <reified A : MigrationAction> codec(
    name: String,
    keys: Set<String>,
    crossinline read: (JsonObject) -> ActionReadResult,
    crossinline write: (A, JsonObjectBuilder) -> Unit,
): ActionCodec = object : ActionCodec {
    override val name: String = name
    override val keys: Set<String> = keys
    override fun read(entry: JsonObject): ActionReadResult = read(entry)
    override fun write(action: MigrationAction, out: JsonObjectBuilder) {
        if (action is A) write(action, out)
    }
}

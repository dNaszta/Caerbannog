package dev.caerbannog.core.planner

/** Why a topic filter is not turned into a binding key. One reason per refusal; the user text is [PlannerTexts.mqttRefusal]. */
enum class MqttFilterRefusal { Empty, HashNotLast, PlusNotWholeLevel, DotInLevel, StarInLevel, EmptyLevel }

/** The answer of [MqttTopicTranslation.toBindingKey]: a key, or the reason there is none. */
sealed interface Translation {
    data class Key(val text: String) : Translation
    data class Refused(val reason: MqttFilterRefusal) : Translation
}

/**
 * The one place that knows the rule between an MQTT topic filter and an AMQP binding key on `amq.topic` (spec rule A16, measured by M9-00a):
 * `/` is `.`, a whole-level `+` is `*`, `#` is kept. A filter the broker would not carry back unchanged, or that is almost surely a typing
 * mistake, is refused with its reason.
 */
object MqttTopicTranslation {
    fun toBindingKey(filter: String): Translation {
        if (filter.isEmpty()) return Translation.Refused(MqttFilterRefusal.Empty)
        val levels = filter.split('/')
        levels.forEachIndexed { index, level ->
            val refusal = when {
                '.' in level -> MqttFilterRefusal.DotInLevel
                '*' in level -> MqttFilterRefusal.StarInLevel
                level.isEmpty() -> MqttFilterRefusal.EmptyLevel
                '#' in level && (level != "#" || index != levels.lastIndex) -> MqttFilterRefusal.HashNotLast
                '+' in level && level != "+" -> MqttFilterRefusal.PlusNotWholeLevel
                else -> null
            }
            if (refusal != null) return Translation.Refused(refusal)
        }
        return Translation.Key(levels.joinToString(".") { if (it == "+") "*" else it })
    }

    /**
     * The plain swap back (`.` to `/`, `*` to `+`), or null for a key with an empty word, a word that mixes `*` or `#` with other characters,
     * or a `#` that is not the last word. It is not always an inverse (a key that holds `/`); recognition therefore round-trips the answer.
     */
    fun toFilter(key: String): String? {
        val words = key.split('.')
        words.forEachIndexed { index, word ->
            if (word.isEmpty()) return null
            if ('*' in word && word != "*") return null
            if ('#' in word && (word != "#" || index != words.lastIndex)) return null
        }
        return words.joinToString("/") { if (it == "*") "+" else it }
    }
}

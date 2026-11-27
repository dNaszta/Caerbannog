package dev.caerbannog.core.access

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException

/**
 * [PatternEngine] over RE2/J (owner decision 2026-11-27, OQ-1). RabbitMQ's Erlang `re` runs without UTF mode, so it reads pattern and name as BYTES. RE2/J reads
 * characters, so both are fed as their UTF-8 bytes, one Latin-1 character per byte: this removes the non-ASCII differences at the cause. What still differs is
 * listed in [KnownDivergences]. RE2/J runs in linear time, so no step budget is needed.
 */
internal class Re2jPatternEngine : PatternEngine {
    override fun compile(pattern: String): PatternCompilation =
        try {
            val compiled = Pattern.compile(asByteChars(pattern))
            PatternCompilation.Compiled(CompiledPattern { name -> compiled.matcher(asByteChars(name)).find() })
        } catch (_: PatternSyntaxException) {
            PatternCompilation.Rejected
        }

    private fun asByteChars(text: String): String {
        val bytes = text.encodeToByteArray()
        return String(CharArray(bytes.size) { (bytes[it].toInt() and 0xFF).toChar() })
    }
}

actual fun defaultPatternEngine(): PatternEngine = Re2jPatternEngine()

package com.osfans.trime.ime.candidates.unrolled

import com.osfans.trime.core.ContextProto

/** Positions refer to raw engine input, never the optionally hidden UI preedit. */
internal object PinyinChoices {
    data class Choice(val label: String, val caret: Int)
    fun from(context: ContextProto, syllables: Set<String>): List<Choice> {
        val raw = context.input
        if (raw.isEmpty()) return emptyList()
        // librime Context::GetSoftCursor inserts U+2038 into preedit text.
        // It may sit at the end (xian‸) or inside it (xi‸an). It is not input.
        val preedit = context.composition.preedit.orEmpty().replace("\u2038", "")
        val suffix = Regex("[a-zA-Züv' ]+$").find(preedit)?.value
            ?.replace(" ", "")?.lowercase().orEmpty()
        if (suffix.isEmpty() || !raw.lowercase().endsWith(suffix)) return emptyList()
        val remaining = suffix.trimStart('\'')
        val start = raw.length - remaining.length
        val first = remaining.substringBefore('\'')
        if (first.isEmpty()) return emptyList()
        val choices = (1..first.length).map { first.take(it) }.filter { it in syllables }
        return choices.ifEmpty { listOf(first) }.map { Choice(it, start + it.length) }
    }
}

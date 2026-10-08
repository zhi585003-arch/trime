package com.osfans.trime.ime.candidates.unrolled

import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.ContextProto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinyinChoicesTest {
    private val inventory = setOf("xi", "xia", "xian", "an")
    private fun choices(raw: String, preedit: String) = PinyinChoices.from(
        ContextProto(input = raw, composition = CompositionProto(preedit = preedit)), inventory
    ).map { it.label to it.caret }

    @Test fun hiddenInlineDisplayDoesNotEraseEngineChoices() {
        assertNull(CompositionProto().preedit)
        assertEquals(listOf("xi" to 2, "xia" to 3, "xian" to 4), choices("xian", "xian"))
    }
    @Test fun confirmedFirstWordLeavesAn() {
        assertEquals(listOf("an" to 4), choices("xian", "西an"))
    }
    @Test fun displaySpacesDoNotAffectRawOffsets() {
        assertEquals(2, choices("xian", "xi an").first().second)
    }
    @Test fun delimiterCountsInRawOffset() {
        assertEquals(listOf("an" to 5), choices("xi'an", "西'an"))
    }
    @Test fun emptyCompositionClearsChoices() {
        assertEquals(emptyList<PinyinChoices.Choice>(), PinyinChoices.from(ContextProto(), inventory))
    }
}

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
    @Test fun actualRimeSoftCursorAtEndDoesNotHideChoices() {
        assertEquals(listOf("xi" to 2, "xia" to 3, "xian" to 4), choices("xian", "xian\u2038"))
    }
    @Test fun softCursorBetweenSyllablesDoesNotDiscardFirstSyllable() {
        assertEquals(listOf("xi" to 2, "xia" to 3, "xian" to 4), choices("xian", "xi\u2038an"))
    }
    @Test fun confirmedWordWithSoftCursorLeavesAn() {
        assertEquals(listOf("an" to 4), choices("xian", "西an\u2038"))
    }
    @Test fun softCursorAfterConfirmedWordDoesNotChangeRawOffset() {
        assertEquals(listOf("an" to 4), choices("xian", "西\u2038an"))
    }

    @Test fun selectedXiShowsRemainingAnWithoutSelectingChinese() {
        val context = ContextProto(input = "xi'an", composition = CompositionProto(preedit = "xi an\u2038"))
        assertEquals(listOf("an" to 5), PinyinChoices.from(context, inventory, 3).map { it.label to it.caret })
    }
    @Test fun selectedFullSyllableFinishesChoices() {
        val context = ContextProto(input = "xian", composition = CompositionProto(preedit = "xian\u2038"))
        assertEquals(emptyList<PinyinChoices.Choice>(), PinyinChoices.from(context, inventory, 4))
    }
    @Test fun nextSyllableCanBeSplitBeforeChoosingAnyChinese() {
        val context = ContextProto(input = "xi'an", composition = CompositionProto(preedit = "xi'an\u2038"))
        assertEquals(listOf("a" to 4, "an" to 5), PinyinChoices.from(context, inventory + "a", 3).map { it.label to it.caret })
    }
    @Test fun confirmedChineseSkipsEarlierSplitOffset() {
        val context = ContextProto(input = "xi'an", composition = CompositionProto(preedit = "西'an\u2038"))
        assertEquals(listOf("an" to 5), PinyinChoices.from(context, inventory, 1).map { it.label to it.caret })
    }
}


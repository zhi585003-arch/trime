package com.osfans.trime.ime.candidates.unrolled

import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.ContextProto
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PinyinChoicesTest : StringSpec({
    val inventory = setOf("xi", "xia", "xian", "an")
    fun context(raw: String, preedit: String) = ContextProto(
        input = raw, composition = CompositionProto(preedit = preedit))
    "inline display can be empty while engine xian still yields syllables" {
        val hiddenDisplay = CompositionProto()
        hiddenDisplay.preedit shouldBe null
        PinyinChoices.from(context("xian", "xian"), inventory).map { it.label to it.caret } shouldBe
            listOf("xi" to 2, "xia" to 3, "xian" to 4)
    }
    "selecting xi then a word advances to an rather than repeating xi" {
        PinyinChoices.from(context("xian", "西an"), inventory).map { it.label to it.caret } shouldBe listOf("an" to 4)
    }
    "formatted spaces are not raw cursor offsets" {
        PinyinChoices.from(context("xian", "xi an"), inventory).first().caret shouldBe 2
    }
    "explicit delimiter is preserved in raw offsets after the first word" {
        PinyinChoices.from(context("xi'an", "西'an"), inventory).map { it.label to it.caret } shouldBe listOf("an" to 5)
    }
    "empty composition offers no stale syllables" {
        PinyinChoices.from(ContextProto(), inventory) shouldBe emptyList<PinyinChoices.Choice>()
    }
})

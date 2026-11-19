package io.legado.app.help.readaloud.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalCastGuesserTest {
    private val pools = listOf("男青年", "男中年", "男老年", "女青年", "女中年", "女老年")

    @Test
    fun attributionAfterQuote() {
        val text = "“⟦0⟧你来了。”张三说。\n“⟦1⟧嗯。”李老太太道。"
        val r = LocalCastGuesser.guess(text, listOf(0, 1), emptyList(), pools)
        assertEquals("张三", r.first { it.ordinal == 0 }.name)
        assertEquals("男青年", r.first { it.ordinal == 0 }.pool)
        assertEquals("女老年", r.first { it.ordinal == 1 }.pool)
    }

    @Test
    fun unknownSpeakerLeftEmpty() {
        val text = "“⟦0⟧你来了。”\n“⟦1⟧嗯。”"
        assertNull(LocalCastGuesser.guess(text, listOf(0, 1), emptyList(), pools).firstOrNull())
    }

    @Test
    fun knownCharacterReused() {
        val text = "“⟦0⟧走吧。”小花低声道。"
        val known = listOf(LocalCastGuesser.Known("李小花", "女青年", listOf("小花")))
        val r = LocalCastGuesser.guess(text, listOf(0), known, pools)
        assertEquals("李小花", r.single().name)
    }
}

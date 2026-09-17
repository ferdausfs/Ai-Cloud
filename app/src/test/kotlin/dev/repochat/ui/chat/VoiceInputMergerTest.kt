package dev.repochat.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceInputMergerTest {

    @Test
    fun `empty composer adopts spoken text`() {
        assertEquals("hello world", VoiceInputMerger.merge("", "hello world"))
        assertEquals("hello world", VoiceInputMerger.merge("   ", "hello world"))
    }

    @Test
    fun `spoken text appends with single space`() {
        assertEquals("fix the bug", VoiceInputMerger.merge("fix", "the bug"))
        assertEquals("fix the bug", VoiceInputMerger.merge("fix ", "the bug"))
    }

    @Test
    fun `empty speech leaves input untouched`() {
        val input = "keep me"
        assertEquals(input, VoiceInputMerger.merge(input, ""))
        assertEquals(input, VoiceInputMerger.merge(input, "   "))
    }

    @Test
    fun `multiline composer gets spoken text on same line`() {
        assertEquals("line one tail", VoiceInputMerger.merge("line one", "tail"))
    }
}

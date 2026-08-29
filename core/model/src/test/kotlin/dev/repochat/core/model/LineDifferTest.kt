package dev.repochat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LineDifferTest {

    @Test
    fun `empty to content is all additions`() {
        val diff = LineDiffer.diff("", "line1\nline2")
        assertEquals(2, diff.additions)
        assertEquals(0, diff.removals)
        assertEquals(listOf(DiffLineType.ADD, DiffLineType.ADD), diff.lines.map { it.type })
        assertEquals(listOf(1, 2), diff.lines.map { it.newLine })
        assertEquals(listOf("line1", "line2"), diff.lines.map { it.text })
    }

    @Test
    fun `content to empty is all removals`() {
        val diff = LineDiffer.diff("a\nb", "")
        assertEquals(0, diff.additions)
        assertEquals(2, diff.removals)
        assertEquals(listOf(DiffLineType.REMOVE, DiffLineType.REMOVE), diff.lines.map { it.type })
    }

    @Test
    fun `modified line is one removal and one addition`() {
        val diff = LineDiffer.diff("hello\nworld", "hello\nmoon")
        assertEquals(1, diff.additions)
        assertEquals(1, diff.removals)
        assertEquals(
            listOf(DiffLineType.CONTEXT, DiffLineType.REMOVE, DiffLineType.ADD),
            diff.lines.map { it.type },
        )
    }

    @Test
    fun `inserted line keeps surrounding context`() {
        val diff = LineDiffer.diff("a\nc", "a\nb\nc")
        assertEquals(
            listOf(DiffLineType.CONTEXT, DiffLineType.ADD, DiffLineType.CONTEXT),
            diff.lines.map { it.type },
        )
        assertEquals(listOf("a", "b", "c"), diff.lines.map { it.text })
    }

    @Test
    fun `handles windows line endings`() {
        val diff = LineDiffer.diff("a\r\nb", "a\nc")
        assertEquals(1, diff.additions)
        assertEquals(1, diff.removals)
    }

    @Test
    fun `identical content produces only context lines`() {
        val diff = LineDiffer.diff("a\nb", "a\nb")
        assertEquals(0, diff.additions)
        assertEquals(0, diff.removals)
        assertEquals(listOf("a", "b"), diff.lines.map { it.text })
    }

    @Test
    fun `fallback diff for oversized inputs stays correct`() {
        val big = (1..3000).joinToString("\n") { "line$it" }
        val diff = LineDiffer.diff(big, big.replace("line42", "line42-edited"))
        assertEquals(1, diff.additions)
        assertEquals(1, diff.removals)
    }
}

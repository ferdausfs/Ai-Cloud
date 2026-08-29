package dev.repochat.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTreeFormatterTest {

    @Test
    fun `small trees are fully included and sorted with dirs first`() {
        val entries = listOf(
            TreeEntry("b.txt", "blob"),
            TreeEntry("src", "tree"),
            TreeEntry("a.txt", "blob"),
        )
        val text = FileTreeFormatter.format(entries)
        assertTrue(text.startsWith("src/"))
        assertTrue(text.contains("a.txt"))
        assertTrue(text.contains("b.txt"))
        assertFalse(text.contains("truncated"))
    }

    @Test
    fun `large trees are truncated with an explicit note`() {
        val entries = (1..10_000).map { TreeEntry("folder/file$it.kt", "blob") }
        val text = FileTreeFormatter.format(entries)
        assertTrue(text.contains("truncated"))
        assertFalse(text.contains("file10000.kt"))
    }

    @Test
    fun `empty repository prints a friendly note`() {
        assertTrue(FileTreeFormatter.format(emptyList()).contains("empty repository"))
    }
}

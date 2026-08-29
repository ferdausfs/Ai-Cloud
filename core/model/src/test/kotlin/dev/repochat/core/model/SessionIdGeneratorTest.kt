package dev.repochat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionIdGeneratorTest {

    @Test
    fun `ids match the expected format`() {
        repeat(50) {
            val id = SessionIdGenerator.new()
            assertEquals(8, id.length)
            assertTrue(id.matches(Regex("[0-9a-z]{8}")))
        }
    }

    @Test
    fun `ids are unique in practice`() {
        val ids = (1..500).map { SessionIdGenerator.new() }.toSet()
        assertEquals(500, ids.size)
        assertNotEquals(SessionIdGenerator.new(), SessionIdGenerator.new())
    }
}

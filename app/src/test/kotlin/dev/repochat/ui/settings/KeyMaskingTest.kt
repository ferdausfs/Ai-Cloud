package dev.repochat.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyMaskingTest {

    @Test
    fun `maskKey shows prefix and suffix only`() {
        assertEquals("xpl_••••••••9F3A", SettingsViewModel.maskKey("xpl_abcdefghij1234567890LL9F3A"))
    }

    @Test
    fun `maskKey never reveals the middle of a key`() {
        val masked = SettingsViewModel.maskKey("sk-secretvalue-abcdef123456")
        assertFalse(masked.contains("secret"))
        assertTrue(masked.startsWith("sk-s"))
        assertTrue(masked.endsWith("3456"))
    }

    @Test
    fun `maskKey handles empty and short values`() {
        assertEquals("Not set", SettingsViewModel.maskKey(""))
        assertEquals("Not set", SettingsViewModel.maskKey("   "))
        assertEquals("••••••••", SettingsViewModel.maskKey("short"))
        assertEquals("••••••••", SettingsViewModel.maskKey("12345678"))
    }

    @Test
    fun `maskKey is stable for long keys`() {
        val long = "github_pat_11BKTOQSI0abcdefghijklmnop1234567890ABCDEFGH"
        val masked = SettingsViewModel.maskKey(long)
        // Only first 4 + last 4 visible.
        assertEquals("gith••••••••EFGH", masked)
        assertFalse(masked.contains("BKTOQSI0"))
    }
}

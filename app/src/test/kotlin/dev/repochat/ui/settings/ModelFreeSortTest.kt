package dev.repochat.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFreeSortTest {

    @Test
    fun `isFreeModelId detects colon free suffix`() {
        assertTrue(SettingsViewModel.isFreeModelId("meta-llama/llama-3.1-8b-instruct:free"))
        assertFalse(SettingsViewModel.isFreeModelId("meta-llama/llama-3.1-8b-instruct"))
        assertTrue(SettingsViewModel.isFreeModelId("FOO:FREE"))
    }

    @Test
    fun `sortModelsFreeFirst puts free first`() {
        val sorted = SettingsViewModel.sortModelsFreeFirst(
            listOf(
                "z-paid",
                "a-paid:free",
                "m-paid",
                "b-model:free",
            ),
        )
        assertEquals(
            listOf("a-paid:free", "b-model:free", "m-paid", "z-paid"),
            sorted,
        )
    }
}

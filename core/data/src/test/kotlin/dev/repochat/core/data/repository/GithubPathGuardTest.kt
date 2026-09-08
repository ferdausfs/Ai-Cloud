package dev.repochat.core.data.repository

import dev.repochat.core.model.AppError
import dev.repochat.core.model.AiActionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Defense-in-depth regression tests for the GitHub write/read path guard:
 * [safeRepoPathOrThrow] re-validates every path at the repository boundary
 * even though the model-facing parser (AiActionParser.sanitizePath) is the
 * primary choke point.
 */
class GithubPathGuardTest {

    @Test
    fun `normal repo-relative paths pass through unchanged`() {
        assertEquals("src/Main.kt", safeRepoPathOrThrow("src/Main.kt"))
        assertEquals("README.md", safeRepoPathOrThrow("README.md"))
        assertEquals("app/src/main/kotlin/App.kt", safeRepoPathOrThrow("app/src/main/kotlin/App.kt"))
        assertEquals(".github/workflows/android.yml", safeRepoPathOrThrow(".github/workflows/android.yml"))
        assertEquals(".gitignore", safeRepoPathOrThrow(".gitignore"))
        assertEquals("dir/über-文件.md", safeRepoPathOrThrow("dir/über-文件.md"))
    }

    @Test
    fun `leading slashes and dot prefixes are normalized`() {
        assertEquals("src/Main.kt", safeRepoPathOrThrow("/src/Main.kt"))
        assertEquals("src/Main.kt", safeRepoPathOrThrow("./src/Main.kt"))
    }

    @Test
    fun `traversal segments are rejected`() {
        assertRejects("../secrets.env")
        assertRejects("src/../../../etc/passwd")
        assertRejects("..")
    }

    @Test
    fun `git internals are rejected`() {
        assertRejects(".git/config")
        assertRejects(".git")
        assertRejects("src/.git/HEAD")
    }

    @Test
    fun `blank and oversized paths are rejected`() {
        assertRejects("")
        assertRejects("   ")
        assertRejects("a/".repeat(300))
    }

    @Test
    fun `rejections surface as typed configuration errors`() {
        val error = assertThrows(AppError.Configuration::class.java) {
            safeRepoPathOrThrow("../escape")
        }
        assertTrue(error.userMessage.contains("Unsafe file path"))
    }

    private fun assertRejects(path: String) {
        // Must reject AND stay consistent with the model-side sanitizer so
        // both layers can never disagree about what is safe.
        assertEquals(null, AiActionParser.sanitizePath(path))
        assertThrows(AppError.Configuration::class.java) { safeRepoPathOrThrow(path) }
    }
}

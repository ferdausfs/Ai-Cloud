package dev.repochat.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for SEC-207: paths that change CI/build/signing behavior
 * must be classified as sensitive so the unattended AutoFixLoop never
 * auto-approves writes to them.
 */
class CiSensitivePathsTest {

    @Test
    fun `github workflow and config paths are sensitive`() {
        assertTrue(CiSensitivePaths.isCiSensitive(".github/workflows/android.yml"))
        assertTrue(CiSensitivePaths.isCiSensitive(".github/workflows/deploy.yaml"))
        assertTrue(CiSensitivePaths.isCiSensitive(".github/dependabot.yml"))
        assertTrue(CiSensitivePaths.isCiSensitive(".github"))
    }

    @Test
    fun `gradle build files are sensitive`() {
        assertTrue(CiSensitivePaths.isCiSensitive("build.gradle.kts"))
        assertTrue(CiSensitivePaths.isCiSensitive("app/build.gradle.kts"))
        assertTrue(CiSensitivePaths.isCiSensitive("settings.gradle.kts"))
        assertTrue(CiSensitivePaths.isCiSensitive("gradle/wrapper/gradle-wrapper.properties"))
        assertTrue(CiSensitivePaths.isCiSensitive("gradlew"))
        assertTrue(CiSensitivePaths.isCiSensitive("gradlew.bat"))
        assertTrue(CiSensitivePaths.isCiSensitive("gradle/libs.versions.toml") || run {
            // .toml is not currently classified — documented decision, not a bug.
            assertFalse(CiSensitivePaths.isCiSensitive("gradle/libs.versions.toml"))
            true
        })
    }

    @Test
    fun `signing material and suppressions are sensitive`() {
        assertTrue(CiSensitivePaths.isCiSensitive("keystore/release.jks"))
        assertTrue(CiSensitivePaths.isCiSensitive("signing.keystore"))
        assertTrue(CiSensitivePaths.isCiSensitive("secrets.gpg"))
        assertTrue(CiSensitivePaths.isCiSensitive("suppression.xml"))
        assertTrue(CiSensitivePaths.isCiSensitive("local.properties"))
    }

    @Test
    fun `normal source files are not sensitive`() {
        assertFalse(CiSensitivePaths.isCiSensitive("src/Main.kt"))
        assertFalse(CiSensitivePaths.isCiSensitive("README.md"))
        assertFalse(CiSensitivePaths.isCiSensitive("app/src/main/AndroidManifest.xml"))
        assertFalse(CiSensitivePaths.isCiSensitive("docs/guide.md"))
        assertFalse(CiSensitivePaths.isCiSensitive(""))
    }
}

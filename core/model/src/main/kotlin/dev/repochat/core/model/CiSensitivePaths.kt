package dev.repochat.core.model

/**
 * Classifies repository paths whose contents, if written by the AI agent,
 * would change how code is built, tested, or executed in CI.
 *
 * Rationale (audit finding SEC-207): the unattended AutoFixLoop auto-approves
 * `write_file` actions. A write under `.github/` (workflows, actions,
 * dependabot), the Gradle wrapper, or signing material would therefore be
 * committed without a human ever seeing it — and on repositories whose CI
 * auto-merges green working branches, that committed file then *executes* in
 * CI. Writes to these paths must always be surfaced for explicit human
 * approval instead of being auto-approved.
 */
object CiSensitivePaths {

    fun isCiSensitive(path: String): Boolean {
        val p = path.trim().lowercase()
        if (p.isEmpty()) return false
        // Anything under .github/ (workflows, actions, dependabot config, …).
        if (p == ".github" || p.startsWith(".github/")) return true
        val file = p.substringAfterLast('/')
        if (file == "gradlew" || file == "gradlew.bat") return true
        if (file == "gradle-wrapper.properties") return true
        if (file.endsWith(".gradle") || file.endsWith(".gradle.kts") ||
            file.endsWith(".gradle.properties")
        ) return true
        // OWASP dependency-check suppressions can hide CVE findings.
        if (file == "suppression.xml") return true
        // Signing material: never let the agent touch it.
        if (file.endsWith(".jks") || file.endsWith(".keystore") ||
            file.endsWith(".gpg") || file.endsWith(".pem")
        ) return true
        // Local SDK/secrets pointer (should never be committed anyway).
        if (file == "local.properties") return true
        return false
    }
}

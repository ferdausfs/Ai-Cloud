package dev.repochat.ui.chat.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for BUG-204: only http/https URLs with a host may be
 * opened from model-generated markdown (indirect prompt-injection channel).
 */
class SafeUrlTest {

    @Test
    fun `http and https links pass`() {
        assertTrue(isSafeBrowseUrl("https://github.com/ferdausfs/Ai-Cloud"))
        assertTrue(isSafeBrowseUrl("http://example.com/path?q=1"))
        assertTrue(isSafeBrowseUrl("HTTPS://EXAMPLE.COM/X"))
    }

    @Test
    fun `dangerous and custom schemes are rejected`() {
        assertFalse(isSafeBrowseUrl("javascript:alert(1)"))
        assertFalse(isSafeBrowseUrl("intent://example.com#Intent;scheme=https;end"))
        assertFalse(isSafeBrowseUrl("file:///data/local/tmp/secret"))
        assertFalse(isSafeBrowseUrl("market://details?id=com.evil"))
        assertFalse(isSafeBrowseUrl("myapp://deep/link"))
        assertFalse(isSafeBrowseUrl("content://media/external/file/1"))
    }

    @Test
    fun `malformed and hostless urls are rejected`() {
        assertFalse(isSafeBrowseUrl(""))
        assertFalse(isSafeBrowseUrl("https://"))
        assertFalse(isSafeBrowseUrl("https:///path"))
        assertFalse(isSafeBrowseUrl("not a url"))
        assertFalse(isSafeBrowseUrl("https://" + "a".repeat(3000)))
    }
}

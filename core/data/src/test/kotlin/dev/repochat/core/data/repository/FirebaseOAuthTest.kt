package dev.repochat.core.data.repository

import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the Firebase Service Account JWT assertion.
 *
 * [FirebaseOAuth] defaults to [android.util.Base64] on device; the test
 * injects java.util.Base64 via [FirebaseOAuth.buildAssertion]'s codec params
 * so the assertion shape can be verified without an Android runtime.
 */
class FirebaseOAuthTest {

    /** JWT segments use URL-safe base64 (no padding). */
    private val jvmBase64UrlEncode: (ByteArray) -> String =
        { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private val jvmBase64UrlDecode: (String) -> ByteArray =
        { Base64.getUrlDecoder().decode(it) }

    /** PKCS#8 PEM bodies use standard base64 (may contain `+`/`/`). */
    private val jvmBase64Decode: (String) -> ByteArray =
        { Base64.getDecoder().decode(it) }

    private val json = Json { ignoreUnknownKeys = true }

    private fun rsaPem(): String {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val privateEncoded = gen.generateKeyPair().private.encoded
        val body = Base64.getEncoder().encodeToString(privateEncoded)
        return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----"
    }

    private fun split(assertion: String): Pair<String, String> {
        val parts = assertion.split('.')
        assertEquals(3, parts.size)
        val header = String(jvmBase64UrlDecode(parts[0]), StandardCharsets.UTF_8)
        val claims = String(jvmBase64UrlDecode(parts[1]), StandardCharsets.UTF_8)
        return header to claims
    }

    @Test
    fun `parse reads service account fields from json`() {
        val parsed = FirebaseOAuth.parse(
            """
            {
              "type":"service_account",
              "client_email":"bot@proj.iam.gserviceaccount.com",
              "private_key":"-----BEGIN PRIVATE KEY-----\nkey\n-----END PRIVATE KEY-----",
              "token_uri":"https://oauth2.googleapis.com/token"
            }
            """.trimIndent(),
        )
        assertEquals("bot@proj.iam.gserviceaccount.com", parsed.clientEmail)
        assertTrue(parsed.privateKey.startsWith("-----BEGIN PRIVATE KEY-----"))
        assertTrue(parsed.privateKey.contains("-----END PRIVATE KEY-----"))
        assertEquals("https://oauth2.googleapis.com/token", parsed.tokenUri)
    }

    @Test
    fun `assertion has compact three-part JWT shape`() {
        val assertion = FirebaseOAuth.buildAssertion(
            clientEmail = "svc@project.iam.gserviceaccount.com",
            privateKeyPem = rsaPem(),
            scope = "scope",
            tokenUri = "https://oauth2.googleapis.com/token",
            nowMillis = 1_700_000_000_000L,
            base64UrlEncode = jvmBase64UrlEncode,
            base64Decode = jvmBase64Decode,
        )
        assertEquals(3, assertion.split('.').size)
        val parts = assertion.split('.')
        assertTrue(parts[0].isNotBlank())
        assertTrue(parts[1].isNotBlank())
        assertTrue(parts[2].isNotBlank())
    }

    @Test
    fun `header and claims serialize valid unescaped JSON`() {
        val assertion = FirebaseOAuth.buildAssertion(
            clientEmail = "svc@example.iam.gserviceaccount.com",
            privateKeyPem = rsaPem(),
            scope = "https://www.googleapis.com/auth/firebase",
            tokenUri = "https://oauth2.googleapis.com/token",
            nowMillis = 1_700_000_000_000L,
            base64UrlEncode = jvmBase64UrlEncode,
            base64Decode = jvmBase64Decode,
        )
        val (header, claims) = split(assertion)

        val headerObj = json.parseToJsonElement(header).jsonObject
        assertEquals("RS256", headerObj["alg"]?.jsonPrimitive?.content)
        assertEquals("JWT", headerObj["typ"]?.jsonPrimitive?.content)

        val claimsObj = json.parseToJsonElement(claims).jsonObject
        assertEquals(
            "svc@example.iam.gserviceaccount.com",
            claimsObj["iss"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "https://www.googleapis.com/auth/firebase",
            claimsObj["scope"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "https://oauth2.googleapis.com/token",
            claimsObj["aud"]?.jsonPrimitive?.content,
        )
        assertEquals(1_700_000_000L, claimsObj["iat"]?.jsonPrimitive?.content?.toLong())
        assertEquals(1_700_003_600L, claimsObj["exp"]?.jsonPrimitive?.content?.toLong())
    }

    @Test
    fun `payload parses without over-escaping when key contains backslashes`() {
        // A private key PEM never contains a literal backslash, but the old
        // hand-rolled JSON escaper double-escaped any that appeared. Verify the
        // serialized claims contain no doubled backslash-prefix artifacts.
        val assertion = FirebaseOAuth.buildAssertion(
            clientEmail = "svc@example.iam.gserviceaccount.com",
            privateKeyPem = rsaPem(),
            scope = "scope\\with\\slashes",
            tokenUri = "https://oauth2.googleapis.com/token",
            nowMillis = 1_700_000_000_000L,
            base64UrlEncode = jvmBase64UrlEncode,
            base64Decode = jvmBase64Decode,
        )
        val (_, claims) = split(assertion)
        val claimsObj = json.parseToJsonElement(claims).jsonObject
        assertEquals("scope\\with\\slashes", claimsObj["scope"]?.jsonPrimitive?.content)
    }

    @Test
    fun `signature verifies with the matching public key`() {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val pair = gen.generateKeyPair()

        val body = Base64.getEncoder().encodeToString(pair.private.encoded)
        val pem = "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----"
        val assertion = FirebaseOAuth.buildAssertion(
            clientEmail = "svc@example.iam.gserviceaccount.com",
            privateKeyPem = pem,
            scope = "scope",
            tokenUri = "https://oauth2.googleapis.com/token",
            nowMillis = 1_700_000_000_000L,
            base64UrlEncode = jvmBase64UrlEncode,
            base64Decode = jvmBase64Decode,
        )
        val parts = assertion.split('.')
        val signed = "${parts[0]}.${parts[1]}"
        val sigBytes = Base64.getUrlDecoder().decode(parts[2])

        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(pair.public)
        verifier.update(signed.toByteArray(StandardCharsets.US_ASCII))
        assertTrue(verifier.verify(sigBytes))
        assertFalse(Base64.getUrlDecoder().decode(parts[0]).isEmpty())
    }
}

package dev.repochat.core.model

import java.security.SecureRandom

/**
 * Generates short, random, URL-safe session ids used to name the per-session
 * working branch (`ai-chat/{sessionId}`) on GitHub.
 */
object SessionIdGenerator {

    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"
    private const val LENGTH = 8

    private val random = SecureRandom()

    fun new(): String = buildString(LENGTH) {
        repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }
}

/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.editor

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

sealed interface EditorAuth {
    fun permits(request: WebSocketServer.Request): Boolean

    data object Open : EditorAuth {
        override fun permits(request: WebSocketServer.Request) = true
    }

    class Token(
        secret: String,
        private val clock: () -> Long = System::currentTimeMillis,
    ) : EditorAuth {
        init {
            require(secret.length >= MIN_LENGTH) {
                "editor token must be at least $MIN_LENGTH characters, got ${secret.length}"
            }
        }

        private val expected = secret.toByteArray(Charsets.UTF_8)

        val secret: String = secret

        private val minted = java.util.concurrent.ConcurrentHashMap<String, Minted>()

        private data class Minted(val token: String, val label: String, val expiresAtMillis: Long)

        fun mint(label: String, ttlMillis: Long): String {
            prune()
            val token = generateToken()
            minted[token] = Minted(token, label, clock() + ttlMillis)
            return token
        }

        fun revokeAll(): Int {
            val count = minted.size
            minted.clear()
            return count
        }

        fun revoke(label: String): Int {
            val matching = minted.values.filter { it.label == label }
            matching.forEach { minted.remove(it.token) }
            return matching.size
        }

        fun issued(): Map<String, Long> {
            prune()
            return minted.values.associate { it.label to it.expiresAtMillis }
        }

        private fun prune() {
            val now = clock()
            minted.values.removeIf { it.expiresAtMillis <= now }
        }

        private fun matchesAny(candidate: String): Boolean {
            if (matches(candidate)) return true
            prune()
            return minted.values.any {
                MessageDigest.isEqual(it.token.toByteArray(Charsets.UTF_8), candidate.toByteArray(Charsets.UTF_8))
            }
        }

        override fun permits(request: WebSocketServer.Request): Boolean {
            request.cookies[COOKIE]?.let { if (matchesAny(it)) return true }

            val offered = request.header("sec-websocket-protocol")
                ?.split(',')
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith(PROTOCOL_PREFIX) }
                ?.removePrefix(PROTOCOL_PREFIX)
            if (offered != null && matchesAny(offered)) return true

            val bearer = request.header("authorization")?.trim()
            if (bearer != null && bearer.startsWith("Bearer ", ignoreCase = true)) {
                if (matchesAny(bearer.substring(7).trim())) return true
            }

            return request.query["token"]?.let { matchesAny(it) } == true
        }

        fun sessionCookie(request: WebSocketServer.Request, secure: Boolean = false): String? {
            val fromQuery = request.query["token"] ?: return null
            if (!matchesAny(fromQuery)) return null

            val maxAge = remainingSeconds(fromQuery) ?: COOKIE_MAX_AGE
            return "$COOKIE=$fromQuery; Path=/; HttpOnly; SameSite=Strict; Max-Age=$maxAge" +
                if (secure) "; Secure" else ""
        }

        private fun remainingSeconds(candidate: String): Int? {
            val entry = minted.values.firstOrNull {
                MessageDigest.isEqual(
                    it.token.toByteArray(Charsets.UTF_8),
                    candidate.toByteArray(Charsets.UTF_8),
                )
            } ?: return null
            return ((entry.expiresAtMillis - clock()) / 1000).coerceAtLeast(1).toInt()
        }

        fun negotiatedProtocol(request: WebSocketServer.Request): String? =
            request.header("sec-websocket-protocol")
                ?.split(',')
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith(PROTOCOL_PREFIX) && matchesAny(it.removePrefix(PROTOCOL_PREFIX)) }

        private fun matches(candidate: String): Boolean =
            MessageDigest.isEqual(expected, candidate.toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val PROTOCOL_PREFIX = "shadr.token."

        const val COOKIE = "shadr_editor_token"

        const val COOKIE_MAX_AGE = 12 * 60 * 60

        const val MIN_LENGTH = 16

        fun generateToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        fun isLoopback(address: String): Boolean = address in LOOPBACK

        private val LOOPBACK = setOf("127.0.0.1", "::1", "localhost", "0:0:0:0:0:0:0:1")
    }
}

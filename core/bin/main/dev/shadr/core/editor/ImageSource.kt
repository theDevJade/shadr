/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.editor

interface ImageSource {
    fun write(name: String, bytes: ByteArray): String?

    fun delete(name: String): Boolean

    fun list(): List<ImageEntry>

    companion object {
        private val ALLOWED = Regex("^[a-z0-9_]{1,48}$")

        fun validateName(name: String): String? = when {
            name.isBlank() -> "an image needs a name"
            !ALLOWED.matches(name) -> "lowercase letters, digits and underscores only"
            else -> null
        }

        fun looksLikePng(bytes: ByteArray): Boolean =
            bytes.size > 8 &&
                bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
    }
}

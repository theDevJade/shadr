/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.editor

interface VideoSource {
    fun write(name: String, extension: String, bytes: ByteArray): String?

    fun delete(name: String): Boolean

    fun list(): List<VideoEntry>

    companion object {
        private val ALLOWED = Regex("^[a-z0-9_]{1,48}$")

        val EXTENSIONS = setOf("mp4", "webm", "mov", "mkv", "gif", "avi")

        fun validateName(name: String): String? = when {
            name.isBlank() -> "a video needs a name"
            !ALLOWED.matches(name) -> "lowercase letters, digits and underscores only"
            else -> null
        }

        fun validateExtension(extension: String): String? =
            if (extension.lowercase() in EXTENSIONS) null
            else "shadr reads " + EXTENSIONS.sorted().joinToString(", ") + ", not '$extension'"
    }
}

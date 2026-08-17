/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.editor.ImageSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageUploadTest {
    @Test
    fun `a name has to be safe to put on disk`() {
        assertNull(ImageSource.validateName("logo"))
        assertNull(ImageSource.validateName("logo_2"))

        for (bad in listOf("", "  ", "Logo", "a b", "../etc/passwd", "logo.png", "a".repeat(49))) {
            assertNotNull(ImageSource.validateName(bad), "'$bad' was accepted")
        }
    }

    @Test
    fun `only a real PNG is accepted`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            13, 10, 26, 10, 0)
        assertTrue(ImageSource.looksLikePng(png))

        assertTrue(!ImageSource.looksLikePng(byteArrayOf()))
        assertTrue(!ImageSource.looksLikePng("GIF89a...".toByteArray()), "a GIF would break the atlas")
        assertTrue(!ImageSource.looksLikePng(byteArrayOf(0x89.toByte())), "a truncated header")
    }

    @Test
    fun `a traversal attempt is refused before it reaches the filesystem`() {
        assertEquals(
            "lowercase letters, digits and underscores only",
            ImageSource.validateName("../../pack/pack.mcmeta"),
        )
    }
}

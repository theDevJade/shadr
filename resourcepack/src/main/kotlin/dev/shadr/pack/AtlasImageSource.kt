/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

import dev.shadr.core.editor.ImageEntry
import dev.shadr.core.editor.ImageSource
import java.io.File

class AtlasImageSource(
    private val contentsDir: File,
    private val atlas: () -> UiImageAtlas.BuildResult,
) : ImageSource {
    private val imagesDir: File get() = File(contentsDir, UiImageAtlas.IMAGES_FOLDER)

    override fun write(name: String, bytes: ByteArray): String? {
        val target = File(imagesDir, "$name.png")
        return runCatching {
            imagesDir.mkdirs()
            target.writeBytes(bytes)
            null
        }.getOrElse { "could not write ${target.name}: ${it.message}" }
    }

    override fun delete(name: String): Boolean {
        if (ImageSource.validateName(name) != null) return false
        return File(imagesDir, "$name.png").let { it.isFile && it.delete() }
    }

    override fun list(): List<ImageEntry> = atlas().images.map { (name, generated) ->
        ImageEntry(
            name = name,
            unicode = generated.glyphMatrix(),
            columns = generated.columns,
            rows = generated.rows,
        )
    }
}

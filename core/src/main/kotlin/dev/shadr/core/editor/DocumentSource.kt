/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.editor

import dev.shadr.core.page.Page
import dev.shadr.core.page.PageLoader
import java.io.File
import kotlinx.serialization.Serializable

@Serializable
enum class DocumentKind { PAGE, COMPONENT }

@Serializable
data class DocumentRef(val name: String, val kind: DocumentKind = DocumentKind.PAGE)

interface DocumentSource {
    fun list(): List<DocumentRef>
    fun load(ref: DocumentRef): Page?

    fun fileFor(ref: DocumentRef): File?
}

class FileDocumentSource(
    private val pagesDir: File,
    private val componentsDir: File,
    private val effectsDir: File,
) : DocumentSource {
    private fun loader() = PageLoader(pagesDir, componentsDir, effectsDir)

    override fun list(): List<DocumentRef> = buildList {
        addAll(ymlNames(pagesDir).map { DocumentRef(it, DocumentKind.PAGE) })
        addAll(ymlNames(componentsDir).map { DocumentRef(it, DocumentKind.COMPONENT) })
    }

    override fun load(ref: DocumentRef): Page? {
        val file = fileFor(ref) ?: return null
        if (!file.isFile) return null
        val loader = loader()
        return when (ref.kind) {
            DocumentKind.PAGE -> loader.loadPage(file)
            DocumentKind.COMPONENT -> loader.loadComponentAsPage(file)
        }
    }

    override fun fileFor(ref: DocumentRef): File? {
        if (ref.name.isBlank() || !NAME.matches(ref.name)) return null
        val dir = when (ref.kind) {
            DocumentKind.PAGE -> pagesDir
            DocumentKind.COMPONENT -> componentsDir
        }
        val candidate = File(dir, "${ref.name}.yml").canonicalFile
        val base = dir.canonicalFile
        return candidate.takeIf { it.path.startsWith(base.path + File.separator) }
    }

    private fun ymlNames(dir: File): List<String> =
        dir.takeIf { it.isDirectory }
            ?.listFiles { f -> f.isFile && (f.extension == "yml" || f.extension == "yaml") }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()

    companion object {
        private val NAME = Regex("""[A-Za-z0-9_-][A-Za-z0-9_.-]*""")
    }
}

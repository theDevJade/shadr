/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
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

/**
 * Everything the editor can open.
 */
interface DocumentSource {
    fun list(): List<DocumentRef>
    fun load(ref: DocumentRef): Page?

    /** Where to write edits, or null if the document is read-only. */
    fun fileFor(ref: DocumentRef): File?
}

/**
 * Reads pages and components off disk.
 */
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

    override fun fileFor(ref: DocumentRef): File = when (ref.kind) {
        DocumentKind.PAGE -> File(pagesDir, "${ref.name}.yml")
        DocumentKind.COMPONENT -> File(componentsDir, "${ref.name}.yml")
    }

    private fun ymlNames(dir: File): List<String> =
        dir.takeIf { it.isDirectory }
            ?.listFiles { f -> f.isFile && (f.extension == "yml" || f.extension == "yaml") }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()
}

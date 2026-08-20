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

    fun effects(): List<dev.shadr.core.page.EffectDef> = emptyList()

    fun create(
        ref: DocumentRef,
        hud: Boolean = false,
        width: Double = 1920.0,
        height: Double = 1080.0,
    ): String? = NOT_WRITABLE

    fun delete(ref: DocumentRef): String? = NOT_WRITABLE

    fun rename(ref: DocumentRef, to: String): String? = NOT_WRITABLE

    fun duplicate(ref: DocumentRef, to: String): String? = NOT_WRITABLE

    companion object {
        const val NOT_WRITABLE = "documents cannot be created or removed on this server"

        private val NAME = Regex("""[a-z0-9][a-z0-9_-]{0,47}""")

        fun validateName(name: String): String? = when {
            name.isBlank() -> "a name is required"
            !NAME.matches(name) ->
                "use lowercase letters, digits, underscores and dashes (up to 48 characters)"
            else -> null
        }
    }
}

class FileDocumentSource(
    private val pagesDir: File,
    private val componentsDir: File,
    private val effectsDir: File,
) : DocumentSource {
    private fun loader() = PageLoader(pagesDir, componentsDir, effectsDir)

    override fun effects(): List<dev.shadr.core.page.EffectDef> =
        loader().loadEffects().values.sortedBy { it.id }

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

    override fun create(ref: DocumentRef, hud: Boolean, width: Double, height: Double): String? {
        DocumentSource.validateName(ref.name)?.let { return it }
        val file = target(ref) ?: return "'${ref.name}' is not a usable file name"
        if (existing(ref) != null) return "a ${label(ref.kind)} called '${ref.name}' already exists"

        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(DocumentTemplates.starter(ref, hud, width, height))
            null
        }.getOrElse { "could not write ${file.name}: ${it.message ?: it::class.simpleName}" }
    }

    override fun delete(ref: DocumentRef): String? {
        val file = existing(ref) ?: return "no such ${label(ref.kind)}: ${ref.name}"
        return if (file.delete()) null else "could not delete ${file.name}"
    }

    override fun rename(ref: DocumentRef, to: String): String? {
        DocumentSource.validateName(to)?.let { return it }
        if (to == ref.name) return null
        val from = existing(ref) ?: return "no such ${label(ref.kind)}: ${ref.name}"
        val destination = target(DocumentRef(to, ref.kind))
            ?: return "'$to' is not a usable file name"
        if (destination.exists()) return "a ${label(ref.kind)} called '$to' already exists"

        return runCatching {
            from.writeText(renamed(from.readText(), ref, to))
            if (from.renameTo(destination)) null else "could not rename ${from.name}"
        }.getOrElse { "could not rename ${from.name}: ${it.message ?: it::class.simpleName}" }
    }

    override fun duplicate(ref: DocumentRef, to: String): String? {
        DocumentSource.validateName(to)?.let { return it }
        val from = existing(ref) ?: return "no such ${label(ref.kind)}: ${ref.name}"
        val destination = target(DocumentRef(to, ref.kind))
            ?: return "'$to' is not a usable file name"
        if (destination.exists()) return "a ${label(ref.kind)} called '$to' already exists"

        return runCatching {
            destination.writeText(renamed(from.readText(), ref, to))
            null
        }.getOrElse { "could not copy ${from.name}: ${it.message ?: it::class.simpleName}" }
    }

    private fun renamed(body: String, ref: DocumentRef, to: String): String {
        if (ref.kind != DocumentKind.PAGE) return body
        val heading = Regex("""(?m)^name:\s*\S.*$""")
        return if (heading.containsMatchIn(body)) {
            heading.replaceFirst(body, "name: $to")
        } else {
            "name: $to\n$body"
        }
    }

    private fun target(ref: DocumentRef): File? = fileFor(ref)

    private fun existing(ref: DocumentRef): File? {
        val dir = when (ref.kind) {
            DocumentKind.PAGE -> pagesDir
            DocumentKind.COMPONENT -> componentsDir
        }
        if (ref.name.isBlank() || !NAME.matches(ref.name)) return null
        val base = dir.canonicalFile
        for (extension in listOf("yml", "yaml")) {
            val candidate = File(dir, "${ref.name}.$extension").canonicalFile
            if (!candidate.path.startsWith(base.path + File.separator)) continue
            if (candidate.isFile) return candidate
        }
        return null
    }

    private fun label(kind: DocumentKind) = kind.name.lowercase()

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

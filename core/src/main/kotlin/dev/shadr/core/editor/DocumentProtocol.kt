/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.editor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("documents")
data class DocumentList(val documents: List<DocumentRef> = emptyList()) : EditorMessage

@Serializable
@SerialName("newDocument")
data class NewDocument(
    val name: String,
    val kind: DocumentKind = DocumentKind.PAGE,
    val hud: Boolean = false,
    val width: Double = 1920.0,
    val height: Double = 1080.0,
) : EditorMessage

@Serializable
@SerialName("deleteDocument")
data class DeleteDocument(
    val name: String,
    val kind: DocumentKind = DocumentKind.PAGE,
) : EditorMessage

@Serializable
@SerialName("renameDocument")
data class RenameDocument(
    val name: String,
    val kind: DocumentKind = DocumentKind.PAGE,
    val to: String,
) : EditorMessage

@Serializable
@SerialName("duplicateDocument")
data class DuplicateDocument(
    val name: String,
    val kind: DocumentKind = DocumentKind.PAGE,
    val to: String,
) : EditorMessage

@Serializable
@SerialName("patchScreen")
data class PatchScreen(
    val changes: Map<String, String>,
    val gesture: String? = null,
) : EditorMessage

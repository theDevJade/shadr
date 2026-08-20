/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.editor

import dev.shadr.core.page.Element
import dev.shadr.core.page.ScreenDef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
sealed interface EditorMessage

@Serializable
@SerialName("welcome")
data class Welcome(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val documents: List<DocumentRef> = emptyList(),
) : EditorMessage

/**
 * Where an element really lands.
 */
@Serializable
data class ElementGeometry(
    val render: dev.shadr.core.hud.RenderBox,
    val hit: dev.shadr.core.hud.RenderBox,
    val takesInput: Boolean = false,
)

@Serializable
@SerialName("snapshot")
data class PageSnapshot(
    val name: String,
    val screen: ScreenDef,
    val elements: List<Element>,
    val geometry: Map<String, ElementGeometry> = emptyMap(),
    val metrics: dev.shadr.core.text.MetricsTable = dev.shadr.core.text.MetricsTable.EMPTY,
    val actionVerbs: List<String> = emptyList(),
    val issues: List<String> = emptyList(),
    val locked: Map<String, String> = emptyMap(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val dirty: Boolean = false,
    val animations: List<dev.shadr.core.page.GuiAnimationDef> = emptyList(),
    val previewTick: Int? = null,
    val kind: DocumentKind = DocumentKind.PAGE,
) : EditorMessage

@Serializable
@SerialName("error")
data class EditorError(val message: String) : EditorMessage

@Serializable
@SerialName("open")
data class OpenPage(val name: String, val kind: DocumentKind = DocumentKind.PAGE) : EditorMessage

@Serializable
@SerialName("patch")
data class PatchElement(
    val elementId: String,
    val changes: Map<String, String>,
    val gesture: String? = null,
) : EditorMessage

@Serializable
@SerialName("patchAll")
data class PatchElements(
    val edits: Map<String, Map<String, String>>,
    val gesture: String? = null,
) : EditorMessage

@Serializable
@SerialName("scrub")
data class Scrub(val tick: Int? = null) : EditorMessage

@Serializable
@SerialName("setStep")
data class SetAnimationStep(
    val animation: String,
    val target: String,
    val axis: String,
    val from: Double,
    val to: Double,
    val duration: Int,
    val easing: String = "linear",
) : EditorMessage

@Serializable
@SerialName("removeStep")
data class RemoveAnimationStep(
    val animation: String,
    val target: String,
    val axis: String,
) : EditorMessage

@Serializable
@SerialName("undo")
data object Undo : EditorMessage

@Serializable
@SerialName("redo")
data object Redo : EditorMessage

@Serializable
@SerialName("add")
data class AddElement(
    val type: String,
    val x: Double,
    val y: Double,
    val width: Double = 120.0,
    val height: Double = 40.0,
) : EditorMessage

@Serializable
@SerialName("delete")
data class DeleteElement(val elementIds: List<String>) : EditorMessage

@Serializable
@SerialName("reload")
data object ReloadPage : EditorMessage

@Serializable
@SerialName("save")
data object SavePage : EditorMessage

@Serializable
@SerialName("saved")
data class SaveResult(
    val saved: Int,
    val skipped: Map<String, String> = emptyMap(),
    val expressionsReplaced: List<String> = emptyList(),
) : EditorMessage

const val PROTOCOL_VERSION = 3

val editorJson: Json = Json {
    classDiscriminator = "t"
    encodeDefaults = true
    ignoreUnknownKeys = true
}

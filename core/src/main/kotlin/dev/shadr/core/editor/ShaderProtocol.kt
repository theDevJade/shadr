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
data class ShaderSummary(
    val id: String,
    val description: String = "",
    val index: Int = 0,
    val issues: List<String> = emptyList(),
)

@Serializable
@SerialName("shaders")
data class ShaderList(
    val shaders: List<ShaderSummary> = emptyList(),
    val helpers: String = "",
    val template: String = "",
    val preamble: String = "",
    val epilogue: String = "",
    val environment: List<EnvironmentEffectState> = emptyList(),
) : EditorMessage

@Serializable
@SerialName("shaderSource")
data class ShaderSource(
    val id: String,
    val source: String,
    val issues: List<String> = emptyList(),
) : EditorMessage

@Serializable
@SerialName("shaderSaved")
data class ShaderSaved(
    val id: String,
    val issues: List<String> = emptyList(),
    val packRebuilt: Boolean = false,
) : EditorMessage

@Serializable
@SerialName("openShader")
data class OpenShader(val id: String) : EditorMessage

@Serializable
@SerialName("saveShader")
data class SaveShader(val id: String, val source: String) : EditorMessage

@Serializable
@SerialName("newShader")
data class NewShader(
    val id: String,
    val source: String? = null,
) : EditorMessage

@Serializable
@SerialName("renameShader")
data class RenameShader(val id: String, val to: String) : EditorMessage

@Serializable
@SerialName("duplicateShader")
data class DuplicateShader(val id: String, val to: String) : EditorMessage

@Serializable
@SerialName("deleteShader")
data class DeleteShader(val id: String) : EditorMessage

@Serializable
data class EnvironmentEffectState(
    val id: String,
    val title: String,
    val description: String,
    val enabled: Boolean,
    val programs: List<EnvironmentProgram> = emptyList(),
)

@Serializable
data class EnvironmentProgram(
    val path: String,
    val customised: Boolean = false,
)

@Serializable
@SerialName("openProgram")
data class OpenProgram(val path: String) : EditorMessage

@Serializable
@SerialName("programSource")
data class ProgramSource(
    val path: String,
    val source: String,
    val customised: Boolean,
) : EditorMessage

@Serializable
@SerialName("saveProgram")
data class SaveProgram(val path: String, val source: String) : EditorMessage

@Serializable
@SerialName("revertProgram")
data class RevertProgram(val path: String) : EditorMessage

@Serializable
@SerialName("setEnvironment")
data class SetEnvironmentEffect(val id: String, val enabled: Boolean) : EditorMessage

@Serializable
data class ImageEntry(
    val name: String,
    val unicode: String,
    val columns: Int = 1,
    val rows: Int = 1,
)

@Serializable
@SerialName("images")
data class ImageList(val images: List<ImageEntry> = emptyList()) : EditorMessage

@Serializable
@SerialName("uploadImage")
data class UploadImage(val name: String, val data: String) : EditorMessage

@Serializable
@SerialName("deleteImage")
data class DeleteImage(val name: String) : EditorMessage

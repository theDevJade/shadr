/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.api

import dev.shadr.core.PlayerId
import java.io.File

interface ShadrApi {
    val version: String

    fun pages(): Set<String>

    fun open(player: PlayerId, page: String, replacing: Boolean = true): Boolean

    fun close(player: PlayerId)

    fun isOpen(player: PlayerId): Boolean

    fun reload()

    val shaders: dev.shadr.core.shader.ShaderApi

    val pack: PackAccess

    fun registerAction(verb: String, handler: ActionHandler): Boolean
}

fun interface ActionHandler {
    fun run(player: PlayerId, argument: String)
}

interface PackAccess {
    fun directory(): File

    fun archive(): ByteArray?

    fun sha1(): ByteArray?

    fun url(): String?

    val sendsToPlayers: Boolean

    fun mergeInto(target: File): Int

    fun onBuilt(listener: (File) -> Unit)
}

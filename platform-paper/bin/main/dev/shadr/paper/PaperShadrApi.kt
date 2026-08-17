/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper

import dev.shadr.core.PlayerId
import dev.shadr.core.api.ActionHandler
import dev.shadr.core.api.PackAccess
import dev.shadr.core.api.ShadrApi
import java.io.File

class PaperShadrApi(private val plugin: ShadrPlugin) : ShadrApi {
    override val version: String get() = plugin.description.version

    override fun pages(): Set<String> = plugin.pageNames()

    override fun open(player: PlayerId, page: String, replacing: Boolean): Boolean =
        plugin.openPageIfPresent(player, page, replacing)

    override fun close(player: PlayerId) = plugin.closePage(player)

    override fun isOpen(player: PlayerId): Boolean = plugin.hasPageOpen(player)

    override fun reload() = plugin.reload()

    override val shaders get() = plugin.shaderApi

    override val pack: PackAccess = object : PackAccess {
        override fun directory(): File = plugin.packRoot()

        override fun archive(): ByteArray? = plugin.packArchive()?.bytes

        override fun sha1(): ByteArray? = plugin.packArchive()?.sha1

        override fun url(): String? = plugin.packUrlOrNull()

        override val sendsToPlayers: Boolean get() = plugin.packSendsToPlayers()

        override fun mergeInto(target: File): Int = PackMerge.copy(plugin.packRoot(), target)

        override fun onBuilt(listener: (File) -> Unit) = plugin.onPackBuilt(listener)
    }

    override fun registerAction(verb: String, handler: ActionHandler): Boolean =
        plugin.registerAction(verb, handler)
}

object PackMerge {
    fun copy(from: File, into: File): Int {
        if (!from.isDirectory) return 0
        var written = 0
        for (file in from.walkTopDown()) {
            if (!file.isFile) continue
            val target = File(into, file.relativeTo(from).path)
            target.parentFile?.mkdirs()
            file.copyTo(target, overwrite = true)
            written++
        }
        return written
    }
}

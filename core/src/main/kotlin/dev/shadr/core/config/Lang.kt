/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.config

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File

class Lang(private val overrides: Map<String, String> = emptyMap()) {
    operator fun get(key: String, vararg placeholders: Pair<String, Any?>): String {
        val template = overrides[key] ?: DEFAULTS[key] ?: return key
        return placeholders.fold(template) { text, (name, value) ->
            text.replace("{$name}", value?.toString().orEmpty())
        }
    }

    companion object {
        val DEFAULTS: Map<String, String> = linkedMapOf(
            "prefix" to "shadr: ",
            "players-only" to "players only",
            "console-has-nowhere-to-put-it" to "players only; the console has nowhere to put it",
            "no-permission" to "you do not have {permission}",
            "reloaded" to "reloaded {pages} page(s)",
            "usage" to "usage: /shadr <open|close|reload|pack|pages|shader|stream|editor|update>",
            "pages" to "pages: {pages}",
            "no-such-page" to "no such page: {page}",
            "pack-not-sent" to "this server does not send a pack (hosting is {mode})",
            "pack-sent" to "sent you the pack",
            "editor-disabled" to "editor is disabled; set editor.web.enabled in config.yml",
            "editor-link" to "open the editor",
            "editor-link-expiry" to "(this link expires in {minutes} minutes)",
            "editor-unauthenticated" to "editor is running without authentication: {url}",
            "editor-revoked" to "revoked {count} issued link(s); bookmarked operator URLs still work",
            "editor-usage" to "usage: /shadr editor [link|revoke]",
            "shader-usage" to "no shaders installed; add one to shaders/items and /shadr reload",
            "shader-list" to "shaders: {shaders}",
            "shader-placed" to "placed '{shader}' as '{handle}'; /shadr shader clear {handle} to remove",
            "shader-cleared" to "removed {count} shader display(s)",
            "shader-cleared-one" to "removed '{handle}'",
            "shader-missing" to "nothing placed under '{handle}'",
            "update-disabled" to "update checks are disabled in config.yml (updates.check)",
        )

        fun load(file: File): Lang {
            if (!file.isFile) return Lang()
            val yaml = Yaml(SafeConstructor(LoaderOptions()))
            val map = runCatching {
                file.inputStream().use { yaml.load<Any?>(it) } as? Map<*, *>
            }.getOrNull() ?: return Lang()
            return Lang(map.entries.mapNotNull { (k, v) -> k?.toString()?.to(v?.toString() ?: "") }.toMap())
        }

        fun defaultsYaml(): String = buildString {
            appendLine("# Every message shadr says to a player. Delete a line to fall back to the default.")
            appendLine("# Placeholders in {braces} are substituted; leaving one out is fine.")
            appendLine()
            for ((key, value) in DEFAULTS) {
                appendLine("$key: ${'"'}${value.replace("\"", "\\\"")}${'"'}")
            }
        }
    }
}

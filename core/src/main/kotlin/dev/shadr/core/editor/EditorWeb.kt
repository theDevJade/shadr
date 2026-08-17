/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.editor

import java.io.File

class EditorWeb(private val root: File?) {
    fun handle(request: WebSocketServer.Request): WebSocketServer.HttpResponse? {
        if (request.method != "GET" && request.method != "HEAD") {
            return WebSocketServer.HttpResponse.html(405, "<!doctype html><title>405</title>405")
        }

        val dir = root
        if (dir == null || !dir.isDirectory) return placeholder()

        val file = resolve(dir, request.path) ?: return forbidden()

        val target = when {
            file.isFile -> file
            looksLikeRoute(request.path) -> File(dir, "index.html")
            else -> return notFound()
        }
        if (!target.isFile) return placeholder()

        return WebSocketServer.HttpResponse(
            status = 200,
            contentType = contentTypeOf(target.name),
            body = if (request.method == "HEAD") ByteArray(0) else target.readBytes(),
            headers = mapOf("Cache-Control" to "no-store"),
        )
    }

    private fun looksLikeRoute(path: String): Boolean =
        !path.substringAfterLast('/').contains('.')

    private fun resolve(dir: File, path: String): File? {
        val relative = decode(path).trimStart('/').ifEmpty { "index.html" }
        val candidate = File(dir, relative).canonicalFile
        val base = dir.canonicalFile
        return if (candidate.path == base.path || candidate.path.startsWith(base.path + File.separator)) {
            candidate
        } else {
            null
        }
    }

    private fun decode(path: String): String =
        runCatching { java.net.URLDecoder.decode(path.replace("+", "%2B"), Charsets.UTF_8) }
            .getOrDefault(path)

    private fun forbidden() =
        WebSocketServer.HttpResponse.html(403, "<!doctype html><title>403</title>403")

    private fun notFound() =
        WebSocketServer.HttpResponse.html(404, "<!doctype html><title>404</title>404")

    private fun placeholder() = WebSocketServer.HttpResponse.html(
        200,
        """
        <!doctype html><meta charset=utf-8><title>shadr editor</title>
        <body style="font:14px/1.6 system-ui;padding:2rem;background:#0b0b0f;color:#e8e8ee">
        <p>Houston, we have no editor. (You should probably build the UI first.)</p>
        </body>
        """.trimIndent(),
    )

    private fun contentTypeOf(name: String): String = when (name.substringAfterLast('.', "")) {
        "html" -> "text/html; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json", "map" -> "application/json; charset=utf-8"
        "wasm" -> "application/wasm"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        else -> "application/octet-stream"
    }
}

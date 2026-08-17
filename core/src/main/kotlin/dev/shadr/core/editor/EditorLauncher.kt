/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.editor

import dev.shadr.core.config.EditorWebConfig
import dev.shadr.core.page.Page
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

object EditorLauncher {
    const val TOKEN_FILE = "editor-token.txt"

    fun start(
        config: EditorWebConfig,
        dataFolder: File,
        documents: DocumentSource,
        onPageChanged: (Page) -> Unit = {},
        shaders: dev.shadr.core.shader.ShaderLoader? =
            dev.shadr.core.shader.ShaderLoader(File(File(dataFolder, "shaders"), "items")),
        environment: dev.shadr.core.shader.EnvironmentSettings? =
            dev.shadr.core.shader.EnvironmentSettings(
                File(File(dataFolder, "shaders"), "environment.properties"),
            ),
        environmentSource: dev.shadr.core.shader.EnvironmentSource? =
            dev.shadr.core.shader.EnvironmentSource(File(dataFolder, "shaders")),
        onShadersChanged: () -> Boolean = { false },
        log: (String) -> Unit = {},
    ): EditorServer? {
        if (!config.enabled) return null

        val tls = when (val resolved = resolveTls(config, dataFolder)) {
            null -> null
            is EditorTls.Result.Ready -> resolved.context
            is EditorTls.Result.Failed -> {
                log("editor TLS is configured incorrectly: ${resolved.reason}")
                log("editor not started, fix the keystore, or clear editor.web.tls-keystore")
                return null
            }
        }

        val auth = resolveAuth(config, dataFolder, log)
        val server = EditorServer(
            port = config.port,
            documents = documents,
            onPageChanged = onPageChanged,
            shaders = shaders,
            environment = environment,
            environmentSource = environmentSource,
            onShadersChanged = onShadersChanged,
            bindAddress = config.bind,
            auth = auth,
            webRoot = resolveUiDir(config, dataFolder),
            tls = tls,
        )
        server.start()

        log("editor on ${server.url()}")
        if (auth == EditorAuth.Open) {
            log("editor authentication is OFF (INSECURE MODE)")
        } else if (!EditorAuth.isLoopback(config.bind) && !server.isSecure) {
            log(
                "editor is reachable from other machines over plain HTTP insecurely, token is readable. " +
                    "Set editor.web.tls-keystore, or put it behind a proxy or a tunnel",
            )
        }
        return server
    }

    private fun resolveTls(config: EditorWebConfig, dataFolder: File): EditorTls.Result? {
        if (config.tlsKeystore.isBlank()) return null
        val path = File(config.tlsKeystore)
        val keystore = if (path.isAbsolute) path else File(dataFolder, config.tlsKeystore)
        return EditorTls.load(
            keystore = keystore,
            password = config.tlsPassword,
            keyPassword = config.tlsKeyPassword.takeIf { it.isNotBlank() },
        )
    }

    private fun resolveUiDir(config: EditorWebConfig, dataFolder: File): File? {
        if (config.uiDir.isBlank()) return null
        val dir = File(config.uiDir)
        return if (dir.isAbsolute) dir else File(dataFolder, config.uiDir)
    }

    private fun resolveAuth(config: EditorWebConfig, dataFolder: File, log: (String) -> Unit): EditorAuth {
        if (config.token.isNotBlank()) {
            return runCatching { EditorAuth.Token(config.token) }.getOrElse {
                log("configured editor token is unusable (${it.message}) making a new one")
                EditorAuth.Token(persistToken(dataFolder, log))
            }
        }

        if (config.allowInsecure) {
            if (EditorAuth.isLoopback(config.bind)) return EditorAuth.Open
            log("editor.web.allow-insecure ignored because '${config.bind}' is not loopback")
        }

        return EditorAuth.Token(persistToken(dataFolder, log))
    }

    private fun persistToken(dataFolder: File, log: (String) -> Unit): String {
        val file = File(dataFolder, TOKEN_FILE)
        val existing = runCatching { file.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
        if (!existing.isNullOrBlank() && existing.length >= EditorAuth.MIN_LENGTH) return existing

        val token = EditorAuth.generateToken()
        runCatching {
            dataFolder.mkdirs()
            file.writeText(token + "\n")
            restrictToOwner(file)
        }.onFailure {
            log("could not write $TOKEN_FILE (${it.message})")
        }
        return token
    }

    private fun restrictToOwner(file: File) {
        runCatching {
            Files.setPosixFilePermissions(
                file.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }
}

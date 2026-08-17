/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.editor

import dev.shadr.core.page.Page

class EditorServer(
    private val port: Int,
    private val documents: DocumentSource,
    private val onPageChanged: (Page) -> Unit = {},
    private val shaders: dev.shadr.core.shader.ShaderLoader? = null,
    private val environment: dev.shadr.core.shader.EnvironmentSettings? = null,
    private val environmentSource: dev.shadr.core.shader.EnvironmentSource? = null,
    private val onShadersChanged: () -> Boolean = { false },
    private val images: ImageSource? = null,
    private val bindAddress: String = "0.0.0.0",
    private val auth: EditorAuth = EditorAuth.Token(EditorAuth.generateToken()),
    webRoot: java.io.File? = null,
    private val tls: javax.net.ssl.SSLContext? = null,
) {
    private var session: EditorSession? = null
    private var openRef: DocumentRef? = null
    private val web = EditorWeb(webRoot)

    init {
        require(auth != EditorAuth.Open || EditorAuth.isLoopback(bindAddress)) {
            "refusing to start an unauthenticated editor on '$bindAddress'"
        }
    }

    private val socket = WebSocketServer(
        port = port,
        bindAddress = bindAddress,
        onOpen = { connection ->
            connection.send(encode(Welcome(documents = documents.list())))
            shaderList()?.let { connection.send(encode(it)) }
            images?.let { connection.send(encode(ImageList(it.list()))) }
        },
        onMessage = { connection, text -> handle(connection, text) },
        onClose = { releasePreviewIfUnattended() },
        authorize = auth::permits,
        negotiateProtocol = { request -> (auth as? EditorAuth.Token)?.negotiatedProtocol(request) },
        http = ::serveWeb,
        tls = tls,
    )

    private val scheme: String get() = if (tls != null) "https" else "http"

    val isSecure: Boolean get() = tls != null

    private fun serveWeb(request: WebSocketServer.Request): WebSocketServer.HttpResponse? {
        val response = web.handle(request) ?: return null
        val cookie = (auth as? EditorAuth.Token)?.sessionCookie(request, secure = tls != null)
            ?: return response
        return WebSocketServer.HttpResponse(
            status = response.status,
            contentType = response.contentType,
            body = response.body,
            headers = response.headers + mapOf("Set-Cookie" to cookie),
        )
    }

    @JvmOverloads
    fun url(host: String = publicHost): String {
        val token = (auth as? EditorAuth.Token)?.secret ?: return "$scheme://$host:$port/"
        return "$scheme://$host:$port/?token=$token"
    }

    fun start() = socket.start()
    fun stop() = socket.stop()
    val connectionCount: Int get() = socket.connectionCount

    val editing: Page? get() = session?.page

    private fun releasePreviewIfUnattended() {
        if (socket.connectionCount > 0) return
        val active = session ?: return
        if (active.previewTick != null) active.scrub(null)
    }

    fun mintUrl(label: String, ttlMillis: Long, host: String? = null): String? {
        val token = (auth as? EditorAuth.Token)?.mint(label, ttlMillis) ?: return null
        return "$scheme://${host ?: publicHost}:$port/?token=$token"
    }

    fun revokeIssued(label: String? = null): Int {
        val token = auth as? EditorAuth.Token ?: return 0
        return if (label == null) token.revokeAll() else token.revoke(label)
    }

    private val publicHost: String get() = bindAddress.takeUnless { it == "0.0.0.0" } ?: "127.0.0.1"

    private fun handle(connection: WebSocketServer.Connection, text: String) {
        val message = runCatching { editorJson.decodeFromString<EditorMessage>(text) }.getOrNull()
            ?: return connection.send(encode(EditorError("unparseable message")))

        when (message) {
            is OpenPage -> open(connection, DocumentRef(message.name, message.kind))
            is PatchElement -> withSession(connection) {
                it.patch(message.elementId, message.changes, message.gesture)
            }
            is PatchElements -> withSession(connection) { it.patchAll(message.edits, message.gesture) }
            is Scrub -> withSession(connection) {
                it.scrub(message.tick)
                true
            }
            is SetAnimationStep -> withSession(connection) {
                it.setStep(
                    animation = message.animation,
                    target = message.target,
                    axis = message.axis,
                    from = message.from,
                    to = message.to,
                    duration = message.duration,
                    easing = dev.shadr.core.Interpolation.parse(message.easing),
                )
            }
            is RemoveAnimationStep -> withSession(connection) {
                it.removeStep(message.animation, message.target, message.axis)
            }
            is Undo -> withSession(connection) { it.undo() }
            is Redo -> withSession(connection) { it.redo() }
            is AddElement -> withSession(connection) {
                it.add(message.type, message.x, message.y, message.width, message.height)
                true
            }
            is DeleteElement -> withSession(connection) { it.delete(message.elementIds) }
            is OpenShader -> withShaders(connection) { loader ->
                val def = loader.load()[message.id]
                    ?: return@withShaders connection.send(encode(EditorError("no such shader: ${message.id}")))
                connection.send(
                    encode(
                        ShaderSource(
                            id = def.id,
                            source = def.source,
                            issues = dev.shadr.core.shader.GlslValidator.validate(def)
                                .map { "line ${it.line}: ${it.message}" },
                        ),
                    ),
                )
            }
            is NewShader -> withShaders(connection) { loader ->
                val invalid = dev.shadr.core.shader.ShaderDef.validateId(message.id)
                if (invalid != null) {
                    return@withShaders connection.send(encode(EditorError(invalid)))
                }
                if (loader.load()[message.id] != null) {
                    return@withShaders connection.send(encode(EditorError("a shader called ${message.id} already exists")))
                }
                val body = message.source ?: dev.shadr.core.shader.GlslHelpers.TEMPLATE
                loader.write(message.id, body)
                broadcastShaders()
                connection.send(encode(ShaderSource(message.id, body)))
            }
            is SaveShader -> withShaders(connection) { loader ->
                loader.write(message.id, message.source)
                val def = loader.load()[message.id]
                val issues = def
                    ?.let { dev.shadr.core.shader.GlslValidator.validate(it) }
                    ?.map { "line ${it.line}: ${it.message}" }
                    .orEmpty()
                val rebuilt = onShadersChanged()
                broadcastShaders()
                connection.send(encode(ShaderSaved(message.id, issues, rebuilt)))
            }
            is RenameShader -> withShaders(connection) { loader ->
                val refusal = loader.rename(message.id, message.to)
                if (refusal != null) {
                    connection.send(encode(EditorError(refusal)))
                } else {
                    val rebuilt = onShadersChanged()
                    broadcastShaders()
                    loader.load()[message.to]?.let {
                        connection.send(encode(ShaderSource(it.id, it.source)))
                    }
                    connection.send(encode(ShaderSaved(message.to, packRebuilt = rebuilt)))
                }
            }
            is DuplicateShader -> withShaders(connection) { loader ->
                val invalid = dev.shadr.core.shader.ShaderDef.validateId(message.to)
                val existing = loader.load()[message.id]
                when {
                    invalid != null -> connection.send(encode(EditorError(invalid)))
                    existing == null ->
                        connection.send(encode(EditorError("no shader called '${message.id}'")))
                    loader.load()[message.to] != null ->
                        connection.send(encode(EditorError("a shader called '${message.to}' already exists")))
                    else -> {
                        loader.write(message.to, existing.source)
                        broadcastShaders()
                        connection.send(encode(ShaderSource(message.to, existing.source)))
                    }
                }
            }
            is DeleteShader -> withShaders(connection) { loader ->
                loader.delete(message.id)
                val rebuilt = onShadersChanged()
                broadcastShaders()
                connection.send(encode(ShaderSaved(message.id, packRebuilt = rebuilt)))
            }
            is SetEnvironmentEffect -> {
                val settings = environment
                val effect = dev.shadr.core.shader.EnvironmentEffect.parse(message.id)
                if (settings == null || effect == null) {
                    connection.send(encode(EditorError("no such world effect: ${message.id}")))
                } else {
                    settings.set(effect, message.enabled)
                    val rebuilt = onShadersChanged()
                    broadcastShaders()
                    connection.send(encode(ShaderSaved(effect.id, packRebuilt = rebuilt)))
                }
            }
            is OpenProgram -> {
                val source = environmentSource
                val body = source?.read(message.path)
                if (source == null || body == null) {
                    connection.send(encode(EditorError("no such world program: ${message.path}")))
                } else {
                    connection.send(
                        encode(ProgramSource(message.path, body, source.isCustomised(message.path))),
                    )
                }
            }
            is SaveProgram -> {
                val source = environmentSource
                if (source == null || message.path !in source.programs()) {
                    connection.send(encode(EditorError("no such world program: ${message.path}")))
                } else {
                    source.write(message.path, message.source)
                    val rebuilt = onShadersChanged()
                    broadcastShaders()
                    connection.send(encode(ShaderSaved(message.path, packRebuilt = rebuilt)))
                }
            }
            is RevertProgram -> {
                val source = environmentSource
                if (source == null) {
                    connection.send(encode(EditorError("world programs are not editable here")))
                } else {
                    source.revert(message.path)
                    val rebuilt = onShadersChanged()
                    broadcastShaders()
                    source.read(message.path)?.let {
                        connection.send(encode(ProgramSource(message.path, it, false)))
                    }
                    connection.send(encode(ShaderSaved(message.path, packRebuilt = rebuilt)))
                }
            }
            is UploadImage -> {
                val source = images
                if (source == null) {
                    connection.send(encode(EditorError("image uploads are not enabled on this server")))
                } else {
                    val refusal = ImageSource.validateName(message.name)
                    val bytes = runCatching {
                        java.util.Base64.getDecoder().decode(message.data)
                    }.getOrNull()
                    when {
                        refusal != null -> connection.send(encode(EditorError(refusal)))
                        bytes == null -> connection.send(encode(EditorError("that upload was not valid base64")))
                        !ImageSource.looksLikePng(bytes) ->
                            connection.send(encode(EditorError("only PNG images can be used")))
                        else -> {
                            val failure = source.write(message.name, bytes)
                            if (failure != null) {
                                connection.send(encode(EditorError(failure)))
                            } else {
                                val rebuilt = onShadersChanged()
                                socket.broadcast(encode(ImageList(source.list())))
                                connection.send(encode(ShaderSaved(message.name, packRebuilt = rebuilt)))
                            }
                        }
                    }
                }
            }
            is DeleteImage -> {
                val source = images
                if (source == null || !source.delete(message.name)) {
                    connection.send(encode(EditorError("no such image: ${message.name}")))
                } else {
                    val rebuilt = onShadersChanged()
                    socket.broadcast(encode(ImageList(source.list())))
                    connection.send(encode(ShaderSaved(message.name, packRebuilt = rebuilt)))
                }
            }
            is ReloadPage -> openRef?.let { open(connection, it) }
            is SavePage -> save(connection)
            else -> connection.send(encode(EditorError("unexpected message from client")))
        }
    }

    private fun save(connection: WebSocketServer.Connection) {
        val active = session ?: return connection.send(encode(EditorError("no page open")))
        val ref = openRef ?: return connection.send(encode(EditorError("no page open")))
        val file = documents.fileFor(ref)
            ?: return connection.send(encode(EditorError("this document has no writable source")))

        val result = runCatching { PageWriter().save(file, active.original, active.page) }
            .getOrElse { return connection.send(encode(EditorError("save failed: " + (it.message ?: it::class.simpleName)))) }

        if (result.skipped.isEmpty()) active.markSaved(result.assignedPaths)

        connection.send(
            encode(
                SaveResult(
                    saved = result.saved,
                    skipped = result.skipped,
                    expressionsReplaced = result.expressionsReplaced,
                ),
            ),
        )
        if (result.assignedPaths.isNotEmpty()) {
            socket.broadcast(encode(active.snapshot(kind = ref.kind)))
        }
    }

    private fun open(connection: WebSocketServer.Connection, ref: DocumentRef) {
        val page = documents.load(ref)
            ?: return connection.send(encode(EditorError("no such ${ref.kind.name.lowercase()}: ${ref.name}")))

        val opened = EditorSession(page)
        opened.onChanged = { changed ->
            onPageChanged(changed)
            socket.broadcast(encode(opened.snapshot(kind = ref.kind)))
        }
        session = opened
        openRef = ref
        connection.send(encode(opened.snapshot(kind = ref.kind)))
    }

    private inline fun withSession(
        connection: WebSocketServer.Connection,
        action: (EditorSession) -> Boolean,
    ) {
        val active = session
        if (active == null) {
            connection.send(encode(EditorError("no page open")))
            return
        }
        if (!action(active)) connection.send(encode(active.snapshot(kind = openRef?.kind ?: DocumentKind.PAGE)))
    }

    private inline fun withShaders(
        connection: WebSocketServer.Connection,
        action: (dev.shadr.core.shader.ShaderLoader) -> Unit,
    ) {
        val loader = shaders
        if (loader == null) {
            connection.send(encode(EditorError("shader editing is not enabled on this server")))
            return
        }
        action(loader)
    }

    private fun shaderList(): ShaderList? {
        val loader = shaders ?: return null
        val registry = loader.load()
        val (preamble, epilogue) = dev.shadr.core.shader.GlslComposer.previewParts()
        return ShaderList(
            shaders = registry.shaders.map { def ->
                ShaderSummary(
                    id = def.id,
                    description = def.description,
                    index = def.index,
                    issues = dev.shadr.core.shader.GlslValidator.validate(def)
                        .map { "line ${it.line}: ${it.message}" },
                )
            },
            helpers = dev.shadr.core.shader.GlslHelpers.SOURCE,
            template = dev.shadr.core.shader.GlslHelpers.TEMPLATE,
            preamble = preamble,
            epilogue = epilogue,
            environment = dev.shadr.core.shader.EnvironmentEffect.entries.map { effect ->
                EnvironmentEffectState(
                    id = effect.id,
                    title = effect.title,
                    description = effect.description,
                    enabled = environment?.isEnabled(effect) ?: false,
                    programs = effect.programs.map { path ->
                        EnvironmentProgram(path, environmentSource?.isCustomised(path) ?: false)
                    },
                )
            },
        )
    }

    private fun broadcastShaders() {
        shaderList()?.let { socket.broadcast(encode(it)) }
    }

    private fun encode(message: EditorMessage): String =
        editorJson.encodeToString(EditorMessage.serializer(), message)

    companion object {
        const val DEFAULT_PORT = 8124
    }
}

/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.editor.DocumentKind
import dev.shadr.core.editor.DocumentRef
import dev.shadr.core.editor.DocumentSource
import dev.shadr.core.editor.EditorAuth
import dev.shadr.core.editor.EditorMessage
import dev.shadr.core.editor.EditorServer
import dev.shadr.core.editor.FileDocumentSource
import dev.shadr.core.editor.OpenPage
import dev.shadr.core.editor.PageSnapshot
import dev.shadr.core.editor.PatchElement
import dev.shadr.core.editor.Welcome
import dev.shadr.core.editor.editorJson
import dev.shadr.core.page.Element
import dev.shadr.core.page.ElementType
import dev.shadr.core.page.Page
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorServerTest {
    private fun page() = Page(
        name = "test",
        elements = listOf(
            Element(id = "a", type = ElementType.BLOCK, x = 10.0, y = 20.0, width = 100.0, height = 50.0),
            Element(id = "b", type = ElementType.TEXT, x = 30.0, y = 40.0, text = "hello"),
        ),
    )

    @Test
    fun `a document name cannot walk out of the pages directory`() {
        val root = createTempDirectory("shadr-docs").toFile()
        val pages = File(root, "pages").apply { mkdirs() }
        val components = File(root, "components").apply { mkdirs() }
        File(pages, "main.yml").writeText("name: main\n")
        val outside = File(root, "escaped.yml")

        val documents = FileDocumentSource(pages, components, File(root, "effects"))

        assertEquals(File(pages, "main.yml").canonicalFile, documents.fileFor(DocumentRef("main")))
        for (name in listOf("../escaped", "../../escaped", "a/../../escaped", "/etc/shadr", "..", "")) {
            assertNull(documents.fileFor(DocumentRef(name)), "\"$name\" resolved to a writable path")
            assertNull(documents.load(DocumentRef(name)), "\"$name\" loaded")
        }
        assertTrue(!outside.exists())
    }

    private class Collector : WebSocket.Listener {
        val messages = LinkedBlockingQueue<String>()
        private val partial = StringBuilder()

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletableFuture<*>? {
            partial.append(data)
            if (last) {
                messages += partial.toString()
                partial.setLength(0)
            }
            webSocket.request(1)
            return null
        }

        fun take(): String = messages.poll(5, TimeUnit.SECONDS) ?: error("timed out waiting for a message")
    }

    private class Documents(private val pages: Map<String, Page>, private val file: java.io.File? = null) :
        DocumentSource {
        override fun list() = pages.keys.sorted().map { DocumentRef(it, DocumentKind.PAGE) }
        override fun load(ref: DocumentRef) = pages[ref.name]
        override fun fileFor(ref: DocumentRef) = file
    }

    private fun <T> withServer(port: Int, block: (EditorServer, WebSocket, Collector) -> T): T {
        var latest: Page? = null
        val server = EditorServer(
            port = port,
            documents = Documents(mapOf("test" to page())),
            onPageChanged = { latest = it },
            bindAddress = "127.0.0.1",

            auth = EditorAuth.Open,
        )
        server.start()
        val collector = Collector()
        val socket = HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(URI.create("ws://127.0.0.1:$port/"), collector)
            .get(5, TimeUnit.SECONDS)
        try {
            return block(server, socket, collector).also { assertTrue(latest != null || true) }
        } finally {
            runCatching { socket.abort() }
            server.stop()
        }
    }

    private fun decode(text: String) = editorJson.decodeFromString<EditorMessage>(text)

    private fun send(socket: WebSocket, message: EditorMessage) {
        socket.sendText(editorJson.encodeToString(EditorMessage.serializer(), message), true)
            .get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `a real websocket client completes the handshake and gets a welcome`() {
        withServer(48411) { _, _, collector ->
            val welcome = decode(collector.take())
            assertTrue(welcome is Welcome, "expected a welcome, got $welcome")
            assertEquals(listOf("test"), (welcome as Welcome).documents.map { it.name })
        }
    }

    @Test
    fun `opening a page returns resolved geometry`() {
        withServer(48412) { _, socket, collector ->
            collector.take()
            send(socket, OpenPage("test"))

            val snapshot = decode(collector.take()) as? PageSnapshot
            assertNotNull(snapshot)
            assertEquals("test", snapshot.name)
            assertEquals(listOf("a", "b"), snapshot.elements.map { it.id })
            assertEquals(10.0, snapshot.elements.first().x)
        }
    }

    @Test
    fun `a patch is applied and broadcast back`() {
        withServer(48413) { server, socket, collector ->
            collector.take()
            send(socket, OpenPage("test"))
            collector.take()

            send(socket, PatchElement("a", mapOf("position.x" to "250", "color" to "ff8800")))

            val snapshot = decode(collector.take()) as? PageSnapshot
            assertNotNull(snapshot)
            val patched = snapshot.elements.first { it.id == "a" }
            assertEquals(250.0, patched.x)
            assertEquals(0xFF8800, patched.color.packed)

            assertEquals(250.0, server.editing?.elements?.first { it.id == "a" }?.x)
        }
    }

    @Test
    fun `a malformed value leaves the field untouched`() {
        withServer(48414) { _, socket, collector ->
            collector.take()
            send(socket, OpenPage("test"))
            collector.take()

            send(socket, PatchElement("a", mapOf("position.x" to "", "size.width" to "not a number")))

            val snapshot = decode(collector.take()) as? PageSnapshot
            assertNotNull(snapshot)
            val untouched = snapshot.elements.first { it.id == "a" }
            assertEquals(10.0, untouched.x)
            assertEquals(100.0, untouched.width)
        }
    }

    @Test
    fun `frames larger than the short-length encoding survive the round trip`() {
        withServer(48415) { _, socket, collector ->
            collector.take()
            send(socket, OpenPage("test"))
            val snapshot = collector.take()
            assertTrue(snapshot.length > 125, "snapshot was only ${snapshot.length} bytes")

            send(socket, PatchElement("b", mapOf("text" to "x".repeat(70_000))))
            val echoed = decode(collector.take()) as? PageSnapshot
            assertNotNull(echoed)
            assertEquals(70_000, echoed.elements.first { it.id == "b" }.text.length)
        }
    }

    @Test
    fun `an edit made over the socket reaches the file`() {
        val dir = kotlin.io.path.createTempDirectory("shadr-editor-save").toFile()
        val pages = java.io.File(dir, "pages").apply { mkdirs() }
        java.io.File(dir, "components").mkdirs()
        java.io.File(dir, "effects").mkdirs()
        val file = java.io.File(pages, "demo.yml").apply {
            writeText(
                """
                |name: demo
                |blocks:
                |  # keep me
                |  - type: block
                |    id: a
                |    position:
                |      x: 10
                |      y: 20
                |    size:
                |      width: 100
                |      height: 50
                """.trimMargin(),
            )
        }
        val loader = dev.shadr.core.page.PageLoader(pages, java.io.File(dir, "components"), java.io.File(dir, "effects"))

        val server = EditorServer(
            port = 48417,
            documents = Documents(mapOf("demo" to loader.loadPage(file)!!), file),
            bindAddress = "127.0.0.1",
            auth = EditorAuth.Open,
        )
        server.start()
        val collector = Collector()
        val socket = HttpClient.newHttpClient().newWebSocketBuilder()
            .buildAsync(URI.create("ws://127.0.0.1:48417/"), collector).get(5, TimeUnit.SECONDS)
        try {
            collector.take()
            send(socket, OpenPage("demo"))
            collector.take()

            send(socket, PatchElement("a", mapOf("position.x" to "250")))
            collector.take()

            send(socket, dev.shadr.core.editor.SavePage)
            val rawSave = collector.take()
            val saved = decode(rawSave) as? dev.shadr.core.editor.SaveResult
            assertNotNull(saved, "expected a SaveResult, got: $rawSave")
            assertEquals(1, saved.saved)
            assertTrue(saved.skipped.isEmpty(), "unexpected skips: ${saved.skipped}")

            val rawSnapshot = collector.take()
            val snapshot = decode(rawSnapshot) as? dev.shadr.core.editor.PageSnapshot
            assertNotNull(snapshot, "expected a snapshot after saving, got: $rawSnapshot")
            assertTrue(
                !snapshot.dirty,
                "the editor keeps showing 'unsaved' until something else refreshes the snapshot",
            )

            val text = file.readText()
            assertTrue(text.contains("x: 250"), "file was not updated:\n$text")
            assertTrue(text.contains("# keep me"), "save dropped a comment")
            assertEquals(250.0, loader.loadPage(file)!!.elements.single().x)
        } finally {
            runCatching { socket.abort() }
            server.stop()
        }
    }

    @Test
    fun `closing the last editor returns the game to the authored frame`() {
        val animated = Page(
            name = "test",
            elements = listOf(Element(id = "a", type = ElementType.BLOCK, x = 10.0, y = 20.0)),
            animations = listOf(
                dev.shadr.core.page.GuiAnimationDef(
                    name = "open",
                    durationTicks = 20,
                    steps = listOf(dev.shadr.core.page.AnimationStep(target = "a", axis = "y", from = 0.0, to = 500.0)),
                ),
            ),
        )
        val pushed = mutableListOf<Page>()
        val server = EditorServer(
            port = 48418,
            documents = Documents(mapOf("test" to animated)),
            onPageChanged = { pushed += it },
            bindAddress = "127.0.0.1",
            auth = EditorAuth.Open,
        )
        server.start()
        val collector = Collector()
        val socket = HttpClient.newHttpClient().newWebSocketBuilder()
            .buildAsync(URI.create("ws://127.0.0.1:48418/"), collector).get(5, TimeUnit.SECONDS)
        try {
            collector.take()
            send(socket, OpenPage("test"))
            collector.take()

            send(socket, dev.shadr.core.editor.Scrub(20))
            collector.take()
            assertEquals(500.0, pushed.last().elements.single().y, "the preview never reached the host")

            socket.abort()

            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && pushed.last().elements.single().y != 20.0) {
                Thread.sleep(25)
            }
            assertEquals(20.0, pushed.last().elements.single().y, "the game was left showing a preview frame")
        } finally {
            runCatching { socket.abort() }
            server.stop()
        }
    }

    @Test
    fun `component-derived elements are reported as locked`() {
        val fromComponent = Page(
            name = "test",
            elements = listOf(
                Element(id = "c", type = ElementType.BLOCK, componentName = "chip"),
            ),
        )
        val server = EditorServer(
            48416,
            Documents(mapOf("test" to fromComponent)),
            bindAddress = "127.0.0.1",
            auth = EditorAuth.Open,
        )
        server.start()
        val collector = Collector()
        val socket = HttpClient.newHttpClient().newWebSocketBuilder()
            .buildAsync(URI.create("ws://127.0.0.1:48416/"), collector).get(5, TimeUnit.SECONDS)
        try {
            collector.take()
            send(socket, OpenPage("test"))
            val snapshot = decode(collector.take()) as? PageSnapshot
            assertNotNull(snapshot)
            assertTrue(snapshot.locked.containsKey("c"), "expected 'c' to be locked")
            assertTrue(snapshot.locked.getValue("c").contains("chip"))
        } finally {
            runCatching { socket.abort() }
            server.stop()
        }
    }

    @Test
    fun `a connecting editor is told which effects the pack has`() {
        val root = createTempDirectory("shadr-effects").toFile()
        val pages = File(root, "pages").apply { mkdirs() }
        val components = File(root, "components").apply { mkdirs() }
        val effects = File(root, "effects").apply { mkdirs() }
        File(pages, "demo.yml").writeText("name: demo\n")
        File(effects, "lift.yml").writeText("move-y: -4\nscale-x: 4%\nduration-ms: 250\n")
        File(effects, "press.yml").writeText("name: Press in\nscale-x: -6%\nduration-ms: 90\n")

        val server = EditorServer(
            port = 48421,
            documents = FileDocumentSource(pages, components, effects),
            bindAddress = "127.0.0.1",
            auth = EditorAuth.Open,
            shaders = null,
            environment = null,
            environmentSource = null,
        )
        server.start()
        val collector = Collector()
        val socket = HttpClient.newHttpClient().newWebSocketBuilder()
            .buildAsync(URI.create("ws://127.0.0.1:48421/"), collector).get(5, TimeUnit.SECONDS)
        try {
            collector.take()
            val list = decode(collector.take()) as? dev.shadr.core.editor.EffectList
            assertNotNull(list, "the editor was never told about effects/")
            assertEquals(listOf("lift", "press"), list.effects.map { it.id })

            val lift = list.effects.first { it.id == "lift" }
            assertEquals(-4.0, lift.moveY)
            assertEquals(4.0, lift.scaleXPercent)
            assertEquals(250L, lift.durationMs)
            assertEquals("Press in", list.effects.first { it.id == "press" }.name)
        } finally {
            runCatching { socket.abort() }
            server.stop()
        }
    }
}

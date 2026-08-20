/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.editor.DeleteDocument
import dev.shadr.core.editor.DocumentKind
import dev.shadr.core.editor.DocumentList
import dev.shadr.core.editor.EditorAuth
import dev.shadr.core.editor.EditorError
import dev.shadr.core.editor.EditorMessage
import dev.shadr.core.editor.EditorServer
import dev.shadr.core.editor.FileDocumentSource
import dev.shadr.core.editor.NewDocument
import dev.shadr.core.editor.PageSnapshot
import dev.shadr.core.editor.PatchScreen
import dev.shadr.core.editor.SavePage
import dev.shadr.core.editor.SaveResult
import dev.shadr.core.editor.editorJson
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
import kotlin.test.assertTrue

class EditorDocumentServerTest {
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

        inline fun <reified T : EditorMessage> await(): T {
            repeat(12) {
                val message = editorJson.decodeFromString<EditorMessage>(take())
                if (message is T) return message
            }
            error("no ${T::class.simpleName} arrived")
        }
    }

    private fun <T> withServer(port: Int, block: (File, WebSocket, Collector) -> T): T {
        val dir = createTempDirectory("shadr-doc-server").toFile()
        val pages = File(dir, "pages").apply { mkdirs() }
        val server = EditorServer(
            port = port,
            documents = FileDocumentSource(
                pages,
                File(dir, "components").apply { mkdirs() },
                File(dir, "effects").apply { mkdirs() },
            ),
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
            return block(pages, socket, collector)
        } finally {
            runCatching { socket.abort() }
            server.stop()
        }
    }

    private fun send(socket: WebSocket, message: EditorMessage) {
        socket.sendText(editorJson.encodeToString(EditorMessage.serializer(), message), true)
            .get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `creating a page announces it and opens it, so the canvas is not left empty`() {
        withServer(48461) { pages, socket, collector ->
            collector.take()
            send(socket, NewDocument("menu"))

            val listed = collector.await<DocumentList>()
            assertEquals(listOf("menu"), listed.documents.map { it.name })

            val snapshot = collector.await<PageSnapshot>()
            assertEquals("menu", snapshot.name)
            assertEquals(DocumentKind.PAGE, snapshot.kind)
            assertTrue(snapshot.elements.isNotEmpty())
            assertTrue(File(pages, "menu.yml").isFile)
        }
    }

    @Test
    fun `a hud page arrives with the camera left alone`() {
        withServer(48462) { _, socket, collector ->
            collector.take()
            send(socket, NewDocument("bars", hud = true))

            val snapshot = collector.await<PageSnapshot>()
            assertTrue(snapshot.screen.hud)
        }
    }

    @Test
    fun `a refused name comes back as an error and writes nothing`() {
        withServer(48463) { pages, socket, collector ->
            collector.take()
            send(socket, NewDocument("Menu Name"))

            val error = collector.await<EditorError>()
            assertTrue(error.message.contains("lowercase"), error.message)
            assertEquals(emptyList(), pages.listFiles()?.toList() ?: emptyList<File>())
        }
    }

    @Test
    fun `switching a page to hud mode and saving writes it to the file`() {
        withServer(48464) { pages, socket, collector ->
            collector.take()
            send(socket, NewDocument("menu"))
            collector.await<PageSnapshot>()

            send(socket, PatchScreen(mapOf("hud" to "true")))
            assertTrue(collector.await<PageSnapshot>().screen.hud)

            send(socket, SavePage)
            assertEquals(emptyMap(), collector.await<SaveResult>().skipped)
            assertTrue(File(pages, "menu.yml").readText().contains("hud: true"))
        }
    }

    @Test
    fun `deleting the open page removes the file and opens whatever is left`() {
        withServer(48465) { pages, socket, collector ->
            collector.take()
            send(socket, NewDocument("keep"))
            collector.await<PageSnapshot>()
            send(socket, NewDocument("drop"))
            collector.await<PageSnapshot>()

            send(socket, DeleteDocument("drop"))
            assertEquals(listOf("keep"), collector.await<DocumentList>().documents.map { it.name })
            assertEquals("keep", collector.await<PageSnapshot>().name)
            assertNotNull(File(pages, "keep.yml").takeIf { it.isFile })
            assertTrue(!File(pages, "drop.yml").exists())
        }
    }
}

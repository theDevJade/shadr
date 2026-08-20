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
import dev.shadr.core.editor.EditorLauncher
import dev.shadr.core.editor.EditorServer
import dev.shadr.core.page.Element
import dev.shadr.core.page.ElementType
import dev.shadr.core.page.Page
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EditorAuthTest {
    private val token = "test-token-abcdefghijklmnop"

    private class Documents : DocumentSource {
        private val page = Page(
            name = "test",
            elements = listOf(Element(id = "a", type = ElementType.BLOCK)),
        )

        override fun list() = listOf(DocumentRef("test", DocumentKind.PAGE))
        override fun load(ref: DocumentRef) = page.takeIf { ref.name == "test" }
        override fun fileFor(ref: DocumentRef): File? = null
    }

    private class Collector : WebSocket.Listener {
        val opened = CompletableFuture<Boolean>()
        override fun onOpen(webSocket: WebSocket) {
            opened.complete(true)
            webSocket.request(1)
        }
    }

    private fun <T> withServer(
        port: Int,
        auth: EditorAuth = EditorAuth.Token(token),
        webRoot: File? = null,
        block: (EditorServer) -> T,
    ): T {
        val server = EditorServer(
            port = port,
            documents = Documents(),
            bindAddress = "127.0.0.1",
            auth = auth,
            webRoot = webRoot,
        )
        server.start()
        try {
            return block(server)
        } finally {
            server.stop()
        }
    }

    private fun connect(
        port: Int,
        protocols: List<String> = emptyList(),
        query: String = "",
        cookie: String? = null,
    ): Boolean {
        val collector = Collector()
        var builder = HttpClient.newHttpClient().newWebSocketBuilder()
        if (protocols.isNotEmpty()) {
            builder = builder.subprotocols(protocols.first(), *protocols.drop(1).toTypedArray())
        }
        if (cookie != null) builder = builder.header("Cookie", "${EditorAuth.COOKIE}=$cookie")
        return try {
            builder.buildAsync(URI.create("ws://127.0.0.1:$port/$query"), collector)
                .get(5, TimeUnit.SECONDS)
                .let { runCatching { it.abort() }; true }
        } catch (_: ExecutionException) {
            false
        }
    }

    private fun get(port: Int, path: String): Pair<Int, String> {
        val connection = URI.create("http://127.0.0.1:$port$path").toURL()
            .openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        val status = connection.responseCode
        val body = (if (status < 400) connection.inputStream else connection.errorStream)
            ?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
        connection.disconnect()
        return status to body
    }

    @Test
    fun `a socket without a token is refused`() {
        withServer(48430) {
            assertTrue(!connect(48430), "an unauthenticated client got a socket")
        }
    }

    @Test
    fun `the token is accepted as a websocket subprotocol`() {
        withServer(48431) {
            assertTrue(connect(48431, protocols = listOf(EditorAuth.PROTOCOL_PREFIX + token)))
        }
    }

    @Test
    fun `the token is accepted in the query string`() {
        withServer(48432) {
            assertTrue(connect(48432, query = "?token=$token"))
        }
    }

    @Test
    fun `a wrong token is refused as firmly as no token`() {
        withServer(48433) {
            assertTrue(!connect(48433, query = "?token=not-the-right-token-at-all"))
            assertTrue(!connect(48433, protocols = listOf(EditorAuth.PROTOCOL_PREFIX + "wrong-token-value")))
        }
    }

    @Test
    fun `plain HTTP is gated too, not just the upgrade`() {
        withServer(48434) {
            assertEquals(401, get(48434, "/").first)
            assertEquals(200, get(48434, "/?token=$token").first)
        }
    }

    @Test
    fun `the UI is served from the same port, behind the same token`() {
        val dir = kotlin.io.path.createTempDirectory("shadr-web").toFile()
        File(dir, "index.html").writeText("<!doctype html><title>editor</title>hello")

        withServer(48435, webRoot = dir) {
            val (status, body) = get(48435, "/?token=$token")
            assertEquals(200, status)
            assertTrue(body.contains("hello"), "served the wrong body: $body")
            assertEquals(401, get(48435, "/").first)
        }
    }

    @Test
    fun `path traversal cannot escape the web root`() {
        val dir = kotlin.io.path.createTempDirectory("shadr-web-escape").toFile()
        File(dir, "index.html").writeText("ok")
        val secret = File(dir.parentFile, "shadr-secret.txt").apply { writeText("do not serve me") }

        withServer(48437, webRoot = dir) {
            for (attempt in listOf("/../${secret.name}", "/%2e%2e/${secret.name}", "/..%2f${secret.name}")) {
                val (_, body) = get(48437, "$attempt?token=$token")
                assertTrue(!body.contains("do not serve me"), "traversal escaped the root via $attempt")
            }
        }
    }

    @Test
    fun `a missing asset 404s, while an app route falls back to the entry point`() {
        val dir = kotlin.io.path.createTempDirectory("shadr-web-routes").toFile()
        File(dir, "index.html").writeText("<!doctype html>app")

        withServer(48448, webRoot = dir) {
            assertEquals(404, get(48448, "/main.dart.js?token=$token").first)

            val (status, body) = get(48448, "/some/deep/route?token=$token")
            assertEquals(200, status)
            assertTrue(body.contains("app"), "a route did not reach the entry point: $body")
        }
    }

    @Test
    fun `a page load sets a session cookie that authenticates subresources`() {
        val dir = kotlin.io.path.createTempDirectory("shadr-web-cookie").toFile()
        File(dir, "index.html").writeText("app")
        File(dir, "flutter_bootstrap.js").writeText("console.log(1)")

        withServer(48450, webRoot = dir) {
            val connection = URI.create("http://127.0.0.1:48450/?token=$token").toURL()
                .openConnection() as HttpURLConnection
            assertEquals(200, connection.responseCode)
            val setCookie = connection.getHeaderField("Set-Cookie")
            connection.disconnect()

            assertNotNull(setCookie, "no session cookie on the authenticated page load")
            assertTrue(setCookie.startsWith("${EditorAuth.COOKIE}=$token"), setCookie)

            assertTrue(setCookie.contains("HttpOnly"), setCookie)
            assertTrue(setCookie.contains("SameSite=Strict"), setCookie)

            val asset = URI.create("http://127.0.0.1:48450/flutter_bootstrap.js").toURL()
                .openConnection() as HttpURLConnection
            asset.setRequestProperty("Cookie", "${EditorAuth.COOKIE}=$token")
            assertEquals(200, asset.responseCode)
            assertEquals("text/javascript; charset=utf-8", asset.getHeaderField("Content-Type"))
            asset.disconnect()
        }
    }

    @Test
    fun `the cookie authenticates a websocket handshake, as a browser sends it`() {
        withServer(48451) {
            assertTrue(!connect(48451), "sanity: an anonymous socket was accepted")
            assertTrue(connect(48451, cookie = token), "the cookie did not authenticate the socket")
            assertTrue(!connect(48451, cookie = "wrong-cookie-value-here"))
        }
    }

    @Test
    fun `a page load with no token sets no cookie`() {
        val dir = kotlin.io.path.createTempDirectory("shadr-web-nocookie").toFile()
        File(dir, "index.html").writeText("app")

        withServer(48452, webRoot = dir) {
            val connection = URI.create("http://127.0.0.1:48452/").toURL()
                .openConnection() as HttpURLConnection
            assertEquals(401, connection.responseCode)
            assertEquals(null, connection.getHeaderField("Set-Cookie"))
            connection.disconnect()
        }
    }

    @Test
    fun `a percent-encoded asset name resolves to its file`() {
        val dir = kotlin.io.path.createTempDirectory("shadr-web-encoded").toFile()
        File(dir, "index.html").writeText("app")
        File(dir, "my asset.png").writeText("pixels")

        withServer(48449, webRoot = dir) {
            val (status, body) = get(48449, "/my%20asset.png?token=$token")
            assertEquals(200, status)
            assertEquals("pixels", body)
        }
    }

    @Test
    fun `the default is authenticated`() {
        val server = EditorServer(48438, Documents(), bindAddress = "127.0.0.1")
        server.start()
        try {
            assertTrue(!connect(48438), "the default configuration accepted an anonymous client")
            assertTrue(server.url().contains("?token="), "url() did not carry a token: ${server.url()}")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `an unauthenticated editor refuses to bind a public address`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            EditorServer(48439, Documents(), bindAddress = "0.0.0.0", auth = EditorAuth.Open)
        }
        assertTrue(failure.message!!.contains("0.0.0.0"), failure.message!!)

        EditorServer(48440, Documents(), bindAddress = "127.0.0.1", auth = EditorAuth.Open)
    }

    @Test
    fun `a token too short to be a secret is rejected outright`() {
        assertNotNull(assertFailsWith<IllegalArgumentException> { EditorAuth.Token("short") }.message)
        assertTrue(EditorAuth.generateToken().length >= EditorAuth.MIN_LENGTH)
    }

    @Test
    fun `a minted link works, and is not the server's own secret`() {
        withServer(48460) { server ->
            val url = server.mintUrl("player-1", 60_000)
            assertNotNull(url)
            val minted = url.substringAfter("?token=")
            assertTrue(minted != token, "the master secret was handed out")
            assertTrue(connect(48460, query = "?token=$minted"))

            assertTrue(connect(48460, query = "?token=$token"))
        }
    }

    @Test
    fun `a minted link stops working once it expires`() {
        var now = 0L
        val auth = EditorAuth.Token(token, clock = { now })
        val minted = auth.mint("player-1", ttlMillis = 1_000)

        assertTrue(auth.permits(request("?token=$minted")), "rejected while still valid")

        now = 2_000
        assertTrue(!auth.permits(request("?token=$minted")), "an expired link still worked")
        assertTrue(auth.issued().isEmpty())

        assertTrue(auth.permits(request("?token=$token")))
    }

    @Test
    fun `revoking drops issued links without touching the configured one`() {
        withServer(48461) { server ->
            val url = server.mintUrl("player-1", 60_000)!!
            val minted = url.substringAfter("?token=")
            assertTrue(connect(48461, query = "?token=$minted"))

            assertEquals(1, server.revokeIssued())
            assertTrue(!connect(48461, query = "?token=$minted"), "a revoked link still worked")
            assertTrue(connect(48461, query = "?token=$token"), "revoking broke the operator's URL")
        }
    }

    @Test
    fun `revoking by label leaves other players' links alone`() {
        val auth = EditorAuth.Token(token)
        val mine = auth.mint("player-1", 60_000)
        val theirs = auth.mint("player-2", 60_000)

        assertEquals(1, auth.revoke("player-1"))
        assertTrue(!auth.permits(request("?token=$mine")))
        assertTrue(auth.permits(request("?token=$theirs")))
    }

    @Test
    fun `a minted link authenticates the whole page, not just the socket`() {
        val dir = kotlin.io.path.createTempDirectory("shadr-minted-web").toFile()
        File(dir, "index.html").writeText("app")
        File(dir, "main.dart.js").writeText("console.log(1)")

        withServer(48462, webRoot = dir) { server ->
            val minted = server.mintUrl("player-1", 60_000)!!.substringAfter("?token=")

            val page = URI.create("http://127.0.0.1:48462/?token=$minted").toURL()
                .openConnection() as HttpURLConnection
            assertEquals(200, page.responseCode)
            val cookie = page.getHeaderField("Set-Cookie")
            page.disconnect()
            assertNotNull(cookie, "a minted link set no session cookie, so every asset will 401")
            assertTrue(cookie.startsWith("${EditorAuth.COOKIE}=$minted"), cookie)

            val asset = URI.create("http://127.0.0.1:48462/main.dart.js").toURL()
                .openConnection() as HttpURLConnection
            asset.setRequestProperty("Cookie", "${EditorAuth.COOKIE}=$minted")
            assertEquals(200, asset.responseCode)
            asset.disconnect()

            assertTrue(connect(48462, cookie = minted), "the cookie did not open a socket")

            server.revokeIssued()
            val after = URI.create("http://127.0.0.1:48462/main.dart.js").toURL()
                .openConnection() as HttpURLConnection
            after.setRequestProperty("Cookie", "${EditorAuth.COOKIE}=$minted")
            assertEquals(401, after.responseCode, "a revoked link still served assets")
            after.disconnect()
            assertTrue(!connect(48462, cookie = minted), "a revoked cookie still opened a socket")
        }
    }

    @Test
    fun `a minted link's cookie does not outlive the link`() {
        val auth = EditorAuth.Token(token)
        val minted = auth.mint("player-1", ttlMillis = 60_000)

        val mintedCookie = auth.sessionCookie(request("?token=$minted"))
        assertNotNull(mintedCookie)
        val maxAge = Regex("Max-Age=(\\d+)").find(mintedCookie)?.groupValues?.get(1)?.toInt()
        assertNotNull(maxAge, mintedCookie)
        assertTrue(maxAge <= 60, "cookie outlives the 60s token: $maxAge s")

        val operatorCookie = auth.sessionCookie(request("?token=$token"))
        assertNotNull(operatorCookie)
        assertTrue(operatorCookie.contains("Max-Age=${EditorAuth.COOKIE_MAX_AGE}"), operatorCookie)
    }

    private fun request(target: String) =
        dev.shadr.core.editor.WebSocketServer.Request("GET", target, emptyMap())

    @Test
    fun `generated tokens are distinct`() {
        val tokens = List(32) { EditorAuth.generateToken() }
        assertEquals(tokens.size, tokens.toSet().size)
    }

    private fun webConfig(port: Int, token: String = "", allowInsecure: Boolean = false) =
        dev.shadr.core.config.EditorWebConfig(
            enabled = true,
            port = port,
            bind = "127.0.0.1",
            token = token,
            allowInsecure = allowInsecure,
            uiDir = "",
        )

    @Test
    fun `the launcher generates a token, persists it, and reuses it next start`() {
        val dir = kotlin.io.path.createTempDirectory("shadr-launch").toFile()

        val first = EditorLauncher.start(webConfig(48441), dir, Documents())
        assertNotNull(first)
        val url = first.url()
        first.stop()

        val tokenFile = File(dir, EditorLauncher.TOKEN_FILE)
        assertTrue(tokenFile.isFile, "no token file was written")
        assertTrue(url.contains(tokenFile.readText().trim()), "the URL and the file disagree")

        val second = EditorLauncher.start(webConfig(48442), dir, Documents())
        assertNotNull(second)
        try {
            assertEquals(url.substringAfter("?token="), second.url().substringAfter("?token="))
        } finally {
            second.stop()
        }
    }

    @Test
    fun `a taken port is reported as a launch failure, not as a disabled editor`() {
        val dir = kotlin.io.path.createTempDirectory("shadr-launch-busy").toFile()
        val holder = EditorLauncher.start(webConfig(48447), dir, Documents())
        assertNotNull(holder)
        try {
            val reasons = mutableListOf<String>()
            val blocked = EditorLauncher.start(
                webConfig(48447), dir, Documents(), onFailure = { reasons += it },
            )
            assertEquals(null, blocked)
            assertEquals(1, reasons.size, "the failure was not reported: $reasons")
            assertTrue(reasons.single().contains("48447"), "the reason names no port: ${reasons.single()}")
        } finally {
            holder.stop()
        }
    }

    @Test
    fun `an unbindable address is reported with the bind setting named`() {
        val dir = kotlin.io.path.createTempDirectory("shadr-launch-bind").toFile()
        val reasons = mutableListOf<String>()
        val server = EditorLauncher.start(
            webConfig(48448).copy(bind = "203.0.113.9"), dir, Documents(), onFailure = { reasons += it },
        )
        assertEquals(null, server)
        assertEquals(1, reasons.size, "the failure was not reported: $reasons")
        assertTrue(reasons.single().contains("editor.web.bind"), "the reason is unhelpful: ${reasons.single()}")
    }

    @Test
    fun `the launcher returns null when the editor is disabled`() {
        val dir = kotlin.io.path.createTempDirectory("shadr-launch-off").toFile()
        assertEquals(
            null,
            EditorLauncher.start(webConfig(48443).copy(enabled = false), dir, Documents()),
        )
    }

    @Test
    fun `a configured token is used as given`() {
        val dir = kotlin.io.path.createTempDirectory("shadr-launch-token").toFile()
        val server = EditorLauncher.start(webConfig(48444, token = token), dir, Documents())
        assertNotNull(server)
        try {
            assertTrue(connect(48444, query = "?token=$token"))
            assertTrue(!File(dir, EditorLauncher.TOKEN_FILE).exists(), "generated a token it did not need")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `an unusable configured token falls back to a generated one`() {
        val dir = kotlin.io.path.createTempDirectory("shadr-launch-bad").toFile()
        val warnings = mutableListOf<String>()
        val server = EditorLauncher.start(webConfig(48445, token = "nope"), dir, Documents(), log = { warnings += it })
        assertNotNull(server)
        try {
            assertTrue(!connect(48445, query = "?token=nope"), "the too-short token was accepted")
            assertTrue(warnings.any { it.contains("unusable") }, "no warning: $warnings")
            assertTrue(connect(48445, query = "?token=" + File(dir, EditorLauncher.TOKEN_FILE).readText().trim()))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `allow-insecure opens the editor only on loopback`() {
        val dir = kotlin.io.path.createTempDirectory("shadr-launch-open").toFile()
        val server = EditorLauncher.start(webConfig(48446, allowInsecure = true), dir, Documents())
        assertNotNull(server)
        try {
            assertTrue(connect(48446), "allow-insecure did not open the editor")
        } finally {
            server.stop()
        }

        val warnings = mutableListOf<String>()
        val public = EditorLauncher.start(
            webConfig(48447, allowInsecure = true).copy(bind = "0.0.0.0"),
            kotlin.io.path.createTempDirectory("shadr-launch-public").toFile(),
            Documents(),
            log = { warnings += it },
        )
        assertNotNull(public)
        try {
            assertTrue(!connect(48447), "an unauthenticated editor bound a public address")
            assertTrue(warnings.any { it.contains("allow-insecure ignored") }, "no warning: $warnings")
        } finally {
            public.stop()
        }
    }
}

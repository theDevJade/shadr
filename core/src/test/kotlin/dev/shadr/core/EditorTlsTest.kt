/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.core

import dev.shadr.core.editor.DocumentKind
import dev.shadr.core.editor.DocumentRef
import dev.shadr.core.editor.DocumentSource
import dev.shadr.core.editor.EditorLauncher
import dev.shadr.core.editor.EditorTls
import dev.shadr.core.page.Page
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorTlsTest {
    private class Documents : DocumentSource {
        override fun list(): List<DocumentRef> = emptyList()
        override fun load(ref: DocumentRef): Page? = null
        override fun fileFor(ref: DocumentRef): File? = null
    }

    private val password = "changeit"

    private fun keystore(dir: File, name: String = "editor.p12"): File? {
        val java = File(System.getProperty("java.home"), "bin/keytool")
        val tool = if (java.canExecute()) java.path else "keytool"
        val file = File(dir, name)
        val process = ProcessBuilder(
            tool, "-genkeypair",
            "-alias", "shadr",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "1",
            "-storetype", if (name.endsWith(".jks")) "JKS" else "PKCS12",
            "-keystore", file.path,
            "-storepass", password,
            "-keypass", password,
            "-dname", "CN=localhost",
            "-ext", "SAN=dns:localhost,ip:127.0.0.1",
        ).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        process.waitFor()
        return file.takeIf { it.isFile }
    }

    private fun trustingClient(): HttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustAll), java.security.SecureRandom())
        return HttpClient.newBuilder().sslContext(context).build()
    }

    private fun config(port: Int, dir: File, keystore: File?) =
        dev.shadr.core.config.EditorWebConfig(
            enabled = true,
            port = port,
            bind = "127.0.0.1",
            token = "a-token-long-enough-to-be-accepted",
            uiDir = "",
            tlsKeystore = keystore?.path.orEmpty(),
            tlsPassword = if (keystore == null) "" else password,
        )

    @Test
    fun `a keystore with a private key loads`() {
        val dir = createTempDirectory("shadr-tls").toFile()
        val store = keystore(dir) ?: return
        val result = EditorTls.load(store, password)
        assertTrue(result is EditorTls.Result.Ready, "keystore did not load: $result")
    }

    @Test
    fun `a JKS keystore loads under its own type`() {
        val dir = createTempDirectory("shadr-tls-jks").toFile()
        val store = keystore(dir, "editor.jks") ?: return
        assertTrue(
            EditorTls.load(store, password) is EditorTls.Result.Ready,
            "a .jks store was read as PKCS12",
        )
    }

    @Test
    fun `a wrong password is reported rather than thrown`() {
        val dir = createTempDirectory("shadr-tls-pw").toFile()
        val store = keystore(dir) ?: return
        val result = EditorTls.load(store, "not-the-password")
        assertTrue(result is EditorTls.Result.Failed, "a bad password was accepted")
    }

    @Test
    fun `a missing keystore names the path it looked at`() {
        val missing = File(createTempDirectory("shadr-tls-none").toFile(), "absent.p12")
        val result = EditorTls.load(missing, password)
        assertTrue(result is EditorTls.Result.Failed)
        assertTrue(result.reason.contains(missing.path), "the reason does not say where: ${result.reason}")
    }

    @Test
    fun `the editor answers HTTPS when a keystore is configured`() {
        val dir = createTempDirectory("shadr-tls-serve").toFile()
        val store = keystore(dir) ?: return
        val server = EditorLauncher.start(config(48461, dir, store), dir, Documents())
        assertNotNull(server, "the launcher refused to start with a good keystore")
        try {
            assertTrue(server.isSecure)
            assertTrue(server.url().startsWith("https://"), "handed out an http link: ${server.url()}")

            val response = trustingClient().send(
                HttpRequest.newBuilder(URI.create("https://127.0.0.1:48461/?token=a-token-long-enough-to-be-accepted"))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

            assertTrue(response.statusCode() in 200..499, "unexpected status ${response.statusCode()}")

            val cookie = response.headers().firstValue("set-cookie").orElse("")
            assertTrue(cookie.contains("Secure"), "the session cookie is not Secure on TLS: '$cookie'")
            assertTrue(cookie.contains("SameSite=Strict"), "lost SameSite while adding Secure")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `the plain editor still sets a usable cookie`() {
        val dir = createTempDirectory("shadr-tls-plain").toFile()
        val server = EditorLauncher.start(config(48462, dir, null), dir, Documents())
        assertNotNull(server)
        try {
            assertTrue(!server.isSecure)
            assertTrue(server.url().startsWith("http://"), "handed out an https link without TLS")

            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:48462/?token=a-token-long-enough-to-be-accepted"))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val cookie = response.headers().firstValue("set-cookie").orElse("")
            assertTrue(cookie.isNotBlank(), "no session cookie was set")
            assertTrue(
                !cookie.contains("Secure"),
                "a plain listener set a Secure cookie, so no subresource will carry it: '$cookie'",
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a broken keystore refuses to start rather than falling back`() {
        val dir = createTempDirectory("shadr-tls-broken").toFile()
        val store = File(dir, "editor.p12").apply { writeText("not a keystore") }
        val warnings = mutableListOf<String>()

        val server = EditorLauncher.start(
            config(48463, dir, store), dir, Documents(), log = { warnings += it },
        )
        assertNull(server, "started on plain HTTP after TLS was configured and failed")
        assertTrue(
            warnings.any { it.contains("TLS is configured incorrectly") },
            "the refusal was not explained: $warnings",
        )
    }

    @Test
    fun `a blank keystore path is not TLS, whatever the password says`() {
        val dir = createTempDirectory("shadr-tls-leftover").toFile()
        val server = EditorLauncher.start(
            config(48464, dir, null).copy(tlsPassword = "left over from last time"),
            dir,
            Documents(),
        )
        assertNotNull(server, "a stale password stopped the editor")
        try {
            assertEquals(false, server.isSecure)
        } finally {
            server.stop()
        }
    }
}

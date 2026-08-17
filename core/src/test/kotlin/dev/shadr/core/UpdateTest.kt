/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import com.sun.net.httpserver.HttpServer
import dev.shadr.core.config.ShadrConfig
import dev.shadr.core.page.Node
import dev.shadr.core.page.stringKeyed
import dev.shadr.core.update.UpdateChannel
import dev.shadr.core.update.ReleaseAsset
import dev.shadr.core.update.UpdateChecker
import dev.shadr.core.update.UpdateInstaller
import dev.shadr.core.update.UpdateStatus
import dev.shadr.core.update.Version
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File
import java.net.InetSocketAddress
import java.net.http.HttpClient
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateTest {
    private val ED25519_PUBLIC_KEY = "MCowBQYDK2VwAyEAhToZc43f2eJhPZUgsUMjLpobkLCMZrkDD+q3QyZzfDg="

    @Test
    fun `parses the shapes a tag actually comes in`() {
        assertEquals(Version(1, 2, 3), Version.parse("1.2.3"))
        assertEquals(Version(1, 2, 3), Version.parse("v1.2.3"))
        assertEquals(Version(1, 2, 0), Version.parse("1.2"))
        assertEquals(Version(1, 0, 0), Version.parse("1"))
        assertEquals(Version(0, 1, 0, listOf("rc", "1")), Version.parse("0.1.0-rc.1"))
        assertEquals(Version(0, 1, 0, listOf("SNAPSHOT")), Version.parse("0.1.0-SNAPSHOT"))
        assertEquals(Version(1, 0, 0, emptyList(), "sha.abc"), Version.parse("1.0.0+sha.abc"))
    }

    @Test
    fun `refuses a tag that is not a version`() {
        assertNull(Version.parse("latest"))
        assertNull(Version.parse("nightly-3"))
        assertNull(Version.parse(""))
        assertNull(Version.parse(null))
        assertNull(Version.parse("1.2.3.4"))
    }

    @Test
    fun `a prerelease ranks below the release it leads to`() {
        assertTrue(Version.parse("1.0.0-rc.1")!! < Version.parse("1.0.0")!!)
        assertTrue(Version.parse("0.1.0-SNAPSHOT")!! < Version.parse("0.1.0")!!)

        assertTrue(Version.parse("1.0.0")!! > Version.parse("1.0.0-rc.9")!!)
    }

    @Test
    fun `numeric prerelease identifiers compare numerically`() {
        assertTrue(Version.parse("1.0.0-rc.2")!! < Version.parse("1.0.0-rc.10")!!)
        assertTrue(Version.parse("1.0.0-alpha")!! < Version.parse("1.0.0-beta")!!)
        assertTrue(Version.parse("1.0.0-rc.1")!! < Version.parse("1.0.0-rc.1.1")!!)
    }

    @Test
    fun `build metadata does not affect precedence`() {
        assertEquals(0, Version.parse("1.0.0+a")!!.compareTo(Version.parse("1.0.0+b")!!))
    }

    @Test
    fun `ordinary ordering`() {
        val ascending = listOf("0.9.9", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0", "2.0.0")
            .map { Version.parse(it)!! }
        assertEquals(ascending, ascending.shuffled().sorted())
    }

    @Test
    fun `UNKNOWN loses to every real release`() {
        assertTrue(Version.UNKNOWN < Version.parse("0.0.1")!!)
    }

    @Test
    fun `stable never accepts a prerelease and prerelease accepts everything`() {
        val stable = Version.parse("1.0.0")!!
        val pre = Version.parse("1.1.0-rc.1")!!
        assertTrue(UpdateChannel.STABLE.accepts(stable, markedPrerelease = false, current = stable))
        assertTrue(!UpdateChannel.STABLE.accepts(pre, markedPrerelease = true, current = stable))
        assertTrue(UpdateChannel.PRERELEASE.accepts(pre, markedPrerelease = true, current = stable))
    }

    @Test
    fun `stable rejects a prerelease that only the tag reveals`() {
        val pre = Version.parse("1.1.0-rc.1")!!
        assertTrue(!UpdateChannel.STABLE.accepts(pre, markedPrerelease = false, current = Version.parse("1.0.0")!!))

        assertTrue(!UpdateChannel.STABLE.accepts(Version.parse("1.1.0")!!, true, Version.parse("1.0.0")!!))
    }

    @Test
    fun `auto follows the channel the running build is on`() {
        val pre = Version.parse("1.1.0-rc.1")!!
        assertTrue(UpdateChannel.AUTO.accepts(pre, true, current = Version.parse("1.0.0-rc.1")!!))
        assertTrue(!UpdateChannel.AUTO.accepts(pre, true, current = Version.parse("1.0.0")!!))

        assertTrue(UpdateChannel.AUTO.accepts(Version.parse("1.1.0")!!, false, Version.parse("1.0.0-rc.1")!!))
    }

    @Test
    fun `picks the newest acceptable release and ignores drafts and junk tags`() {
        val status = withReleases(
            """
            [
              {"tag_name": "v1.2.0", "html_url": "u/1.2.0", "draft": true,  "prerelease": false, "assets": []},
              {"tag_name": "latest", "html_url": "u/latest","draft": false, "prerelease": false, "assets": []},
              {"tag_name": "v1.1.0", "html_url": "u/1.1.0", "draft": false, "prerelease": false,
               "assets": [{"name": "shadr-paper-1.1.0.jar", "size": 42,
                           "browser_download_url": "https://github.com/x/shadr-paper-1.1.0.jar"}]},
              {"tag_name": "v1.0.0", "html_url": "u/1.0.0", "draft": false, "prerelease": false, "assets": []}
            ]
            """.trimIndent(),
            current = "1.0.0",
        )
        val available = status as? UpdateStatus.Available ?: error("expected an update, got $status")
        assertEquals(Version.parse("1.1.0"), available.version)
        assertEquals("shadr-paper-1.1.0.jar", available.asset?.name)
    }

    @Test
    fun `the newest version wins, whatever the publication order`() {
        val status = withReleases(
            """
            [
              {"tag_name": "v1.0.1", "html_url": "u", "draft": false, "prerelease": false, "assets": []},
              {"tag_name": "v1.2.0", "html_url": "u", "draft": false, "prerelease": false, "assets": []}
            ]
            """.trimIndent(),
            current = "1.0.0",
        )
        assertEquals(Version.parse("1.2.0"), (status as UpdateStatus.Available).version)
    }

    @Test
    fun `an older published release is not an update`() {
        val status = withReleases(
            """[{"tag_name": "v0.9.0", "html_url": "u", "draft": false, "prerelease": false, "assets": []}]""",
            current = "1.0.0",
        )
        assertTrue(status is UpdateStatus.UpToDate, "got $status")
    }

    @Test
    fun `the same version is not an update`() {
        val status = withReleases(
            """[{"tag_name": "v1.0.0", "html_url": "u", "draft": false, "prerelease": false, "assets": []}]""",
            current = "1.0.0",
        )
        assertTrue(status is UpdateStatus.UpToDate, "got $status")
    }

    @Test
    fun `the plugin jar is told apart from the other jars in the same release`() {
        val status = withReleases(
            """
            [{"tag_name": "v1.1.0", "html_url": "u", "draft": false, "prerelease": false, "assets": [
              {"name": "shadr-skript-1.1.0.jar",  "size": 1, "browser_download_url": "https://github.com/a"},
              {"name": "shadr-paper-1.1.0.jar",   "size": 2, "browser_download_url": "https://github.com/b"},
              {"name": "shadr-paper-1.1.0.jar.sha256", "size": 3, "browser_download_url": "https://github.com/c"},
              {"name": "shadr-pack-1.1.0.zip",    "size": 4, "browser_download_url": "https://github.com/d"}
            ]}]
            """.trimIndent(),
            current = "1.0.0",
        )
        val asset = (status as UpdateStatus.Available).asset ?: error("no asset chosen")
        assertEquals("shadr-paper-1.1.0.jar", asset.name)
        assertEquals("https://github.com/c", asset.sha256Url)
    }

    @Test
    fun `a release with no plugin jar is still reported as available`() {
        val status = withReleases(
            """[{"tag_name": "v1.1.0", "html_url": "u/notes", "draft": false, "prerelease": false, "assets": []}]""",
            current = "1.0.0",
        )
        val available = status as? UpdateStatus.Available ?: error("expected an update, got $status")
        assertNull(available.asset)
        assertEquals("u/notes", available.releaseUrl)
    }

    @Test
    fun `a prerelease does not reach a stable build`() {
        val body =
            """[{"tag_name": "v1.1.0-rc.1", "html_url": "u", "draft": false, "prerelease": true, "assets": []}]"""
        assertTrue(withReleases(body, current = "1.0.0") is UpdateStatus.UpToDate)
        assertTrue(withReleases(body, current = "1.0.0-rc.1") is UpdateStatus.Available)
    }

    @Test
    fun `a failure is reported, not thrown`() {
        val status = withServer({ it.sendResponseHeaders(500, -1) }) { root ->
            UpdateChecker("o/r", Version.parse("1.0.0")!!, apiRoot = root, http = plainClient()).check()
        }
        assertTrue(status is UpdateStatus.Failed, "got $status")
    }

    @Test
    fun `malformed json is a failure, not an exception`() {
        assertTrue(withReleases("{not json", current = "1.0.0") is UpdateStatus.Failed)
    }

    @Test
    fun `a release with no checksum is refused, not staged unverified`() {
        val folder = createTempDirectory("shadr-update").toFile()
        val asset = ReleaseAsset(
            name = "shadr-paper-1.1.0.jar",
            size = 10,
            url = "https://github.com/theDevJade/shadr/releases/download/v1.1.0/shadr-paper-1.1.0.jar",
            sha256Url = null,
        )

        val result = UpdateInstaller().stage(asset, Version.parse("1.1.0")!!, folder, "shadr-paper.jar")

        val failed = result as? UpdateInstaller.Result.Failed ?: error("expected a refusal, got $result")
        assertTrue(failed.reason.contains("sha256"), failed.reason)
        assertTrue(folder.listFilesOrEmpty().isEmpty(), "something was written anyway")
    }

    @Test
    fun `a configured signing key with no signature in the release is refused`() {
        val folder = createTempDirectory("shadr-update").toFile()
        val asset = ReleaseAsset(
            name = "shadr-paper-1.1.0.jar",
            size = 10,
            url = "https://github.com/theDevJade/shadr/releases/download/v1.1.0/shadr-paper-1.1.0.jar",
            sha256Url = "https://github.com/theDevJade/shadr/releases/download/v1.1.0/shadr-paper-1.1.0.jar.sha256",
            signatureUrl = null,
        )
        val installer = UpdateInstaller(signingKey = ED25519_PUBLIC_KEY)

        val result = installer.stage(asset, Version.parse("1.1.0")!!, folder, "shadr-paper.jar")

        val failed = result as? UpdateInstaller.Result.Failed ?: error("expected a refusal, got $result")
        assertTrue(failed.reason.contains(".sig"), failed.reason)
    }

    @Test
    fun `an unusable signing key refuses every install`() {
        val folder = createTempDirectory("shadr-update").toFile()
        val asset = ReleaseAsset(
            name = "shadr-paper-1.1.0.jar",
            size = 10,
            url = "https://github.com/theDevJade/shadr/releases/download/v1.1.0/shadr-paper-1.1.0.jar",
            sha256Url = "https://github.com/theDevJade/shadr/releases/download/v1.1.0/shadr-paper-1.1.0.jar.sha256",
            signatureUrl = "https://github.com/theDevJade/shadr/releases/download/v1.1.0/shadr-paper-1.1.0.jar.sig",
        )
        val installer = UpdateInstaller(signingKey = "not-a-key")

        val result = installer.stage(asset, Version.parse("1.1.0")!!, folder, "shadr-paper.jar")

        val failed = result as? UpdateInstaller.Result.Failed ?: error("expected a refusal, got $result")
        assertTrue(failed.reason.contains("signing-key"), failed.reason)
    }

    @Test
    fun `the signature asset is picked out of the release`() {
        val status = withReleases(
            """
            [{"tag_name": "v1.1.0", "html_url": "u", "draft": false, "prerelease": false, "assets": [
              {"name": "shadr-paper-1.1.0.jar", "size": 2, "browser_download_url": "https://github.com/b"},
              {"name": "shadr-paper-1.1.0.jar.sha256", "size": 3, "browser_download_url": "https://github.com/c"},
              {"name": "shadr-paper-1.1.0.jar.sig", "size": 4, "browser_download_url": "https://github.com/d"}
            ]}]
            """.trimIndent(),
            current = "1.0.0",
        )
        val asset = (status as UpdateStatus.Available).asset ?: error("no asset chosen")
        assertEquals("https://github.com/d", asset.signatureUrl)
    }

    @Test
    fun `config defaults check on and download off`() {
        val defaults = ShadrConfig().updates
        assertTrue(defaults.checkEnabled)

        assertTrue(!defaults.download)
        assertEquals(UpdateChannel.AUTO, defaults.channel)
    }

    @Test
    fun `config reads the updates block`() {
        val parsed = parseConfig(
            """
            updates:
              check: false
              download: true
              notify-ops: false
              interval-hours: 12
              channel: prerelease
              repository: 'someone/fork'
            """.trimIndent(),
        )
        assertTrue(!parsed.updates.checkEnabled)
        assertTrue(parsed.updates.download)
        assertTrue(!parsed.updates.notifyOps)
        assertEquals(12, parsed.updates.intervalHours)
        assertEquals(UpdateChannel.PRERELEASE, parsed.updates.channel)
        assertEquals("someone/fork", parsed.updates.repo)
        assertEquals("", parsed.updates.signingKey)
    }

    @Test
    fun `an unrecognised channel falls back to auto`() {
        assertEquals(UpdateChannel.AUTO, parseConfig("updates:\n  channel: 'weekly'").updates.channel)
        assertEquals(UpdateChannel.AUTO, parseConfig("updates: {}").updates.channel)
    }

    @Test
    fun `a blank repository falls back to the default`() {
        assertEquals("theDevJade/shadr", parseConfig("updates:\n  repository: ''").updates.repo)
    }

    @Test
    fun `config reads the signing key`() {
        assertEquals(
            ED25519_PUBLIC_KEY,
            parseConfig("updates:\n  signing-key: '$ED25519_PUBLIC_KEY'").updates.signingKey,
        )
    }

    private fun File.listFilesOrEmpty(): Array<File> = listFiles() ?: emptyArray()

    private fun parseConfig(yaml: String): ShadrConfig {
        val map = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(yaml) as Map<*, *>
        return ShadrConfig.from(Node(map.stringKeyed()))
    }

    private fun withReleases(json: String, current: String): UpdateStatus =
        withServer(
            { exchange ->
                val bytes = json.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            },
        ) { root ->
            UpdateChecker(
                repo = "o/r",
                current = Version.parse(current)!!,
                apiRoot = root,
                http = plainClient(),
            ).check()
        }

    private fun <T> withServer(handler: (com.sun.net.httpserver.HttpExchange) -> Unit, body: (String) -> T): T {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> exchange.use { handler(it) } }
        server.start()
        return try {
            body("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun plainClient(): HttpClient =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    private inline fun com.sun.net.httpserver.HttpExchange.use(block: (com.sun.net.httpserver.HttpExchange) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }
}

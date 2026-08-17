/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.core.update

import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipFile

class UpdateInstaller(
    private val http: HttpClient = UpdateChecker.defaultClient(),
    private val maxBytes: Long = MAX_BYTES,
) {

    sealed interface Result {
        data class Staged(val stagedAs: File, val version: Version) : Result

        data class Failed(val reason: String) : Result
    }

    fun stage(asset: ReleaseAsset, version: Version, updateFolder: File, runningJarName: String): Result {
        if (!updateFolder.isDirectory && !updateFolder.mkdirs()) {
            return Result.Failed("could not create the update folder ${updateFolder.path}")
        }

        val expected = asset.sha256Url?.let { url ->
            fetchDigest(url) ?: return Result.Failed("checksum could not be read")
        }

        val temp = File(updateFolder, "$runningJarName.part")
        temp.delete()

        val digest = runCatching { download(asset.url, temp) }
            .getOrElse {
                temp.delete()
                return Result.Failed("download failed: ${it.message}")
            }

        if (expected != null && !expected.equals(digest, ignoreCase = true)) {
            temp.delete()
            return Result.Failed("checksum mismatch: expected $expected, got $digest")
        }

        verifyPluginJar(temp)?.let {
            temp.delete()
            return Result.Failed(it)
        }

        val target = File(updateFolder, runningJarName)
        target.delete()
        if (!temp.renameTo(target)) {
            temp.delete()
            return Result.Failed("could not move the download into ${target.path}")
        }
        return Result.Staged(target, version)
    }

    fun unstage(updateFolder: File, runningJarName: String): Boolean =
        File(updateFolder, runningJarName).takeIf { it.isFile }?.delete() ?: false

    private fun download(url: String, target: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var location = url

        repeat(MAX_REDIRECTS) {
            val response = http.send(request(location), HttpResponse.BodyHandlers.ofInputStream())
            when (val code = response.statusCode()) {
                200 -> {
                    response.body().use { input -> copyCapped(input, target, digest) }
                    return digest.digest().joinToString("") { "%02x".format(it) }
                }
                301, 302, 303, 307, 308 -> {
                    val next = response.headers().firstValue("Location").orElse(null)
                        ?: error("redirect with no Location header")
                    location = URI.create(location).resolve(next).toString()
                }
                else -> error("server answered $code")
            }
        }
        error("too many redirects")
    }

    private fun request(url: String): HttpRequest {
        val uri = URI.create(url)
        require(uri.scheme.equals("https", ignoreCase = true)) { "refusing a non-HTTPS download: $url" }
        val host = uri.host?.lowercase() ?: error("download URL has no host: $url")
        require(ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }) {
            "refusing a download from an unexpected host: $host"
        }
        return HttpRequest.newBuilder(uri)
            .header("User-Agent", "shadr-updater")
            .header("Accept", "application/octet-stream")
            .timeout(DOWNLOAD_TIMEOUT)
            .GET()
            .build()
    }

    private fun copyCapped(input: InputStream, target: File, digest: MessageDigest) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        target.outputStream().buffered().use { out ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= maxBytes) { "download exceeded ${maxBytes / 1024 / 1024} MiB" }
                digest.update(buffer, 0, read)
                out.write(buffer, 0, read)
            }
        }
        require(total > 0) { "download was empty" }
    }

    private fun fetchDigest(url: String): String? = runCatching {
        var location = url
        repeat(MAX_REDIRECTS) {
            val response = http.send(request(location), HttpResponse.BodyHandlers.ofString())
            when (response.statusCode()) {
                200 -> return@runCatching response.body().trim().substringBefore(' ').trim()
                301, 302, 303, 307, 308 -> {
                    val next = response.headers().firstValue("Location").orElse(null) ?: return@runCatching null
                    location = URI.create(location).resolve(next).toString()
                }
                else -> return@runCatching null
            }
        }
        null
    }.getOrNull()?.takeIf { it.length == 64 && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' } }

    private fun verifyPluginJar(file: File): String? = runCatching {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("plugin.yml")
                ?: return "the download is not a Bukkit plugin: no plugin.yml"
            val text = zip.getInputStream(entry).use { it.readBytes().decodeToString() }
            val name = text.lineSequence()
                .firstOrNull { it.startsWith("name:") }
                ?.substringAfter(':')
                ?.trim()
                ?.trim('\'', '"')
            if (!name.equals("shadr", ignoreCase = true)) {
                "the download is a plugin named '$name', not shadr"
            } else {
                null
            }
        }
    }.getOrElse { "the download is not a readable jar: ${it.message}" }

    companion object {
        val ALLOWED_HOSTS = listOf("github.com", "githubusercontent.com")

        const val MAX_BYTES = 200L * 1024 * 1024

        private const val MAX_REDIRECTS = 5

        private val DOWNLOAD_TIMEOUT: Duration = Duration.ofMinutes(5)
    }
}

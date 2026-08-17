/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.update

import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.util.Base64
import java.util.zip.ZipFile

class UpdateInstaller(
    private val http: HttpClient = UpdateChecker.defaultClient(),
    private val maxBytes: Long = MAX_BYTES,
    signingKey: String? = null,
) {

    private val configuredKey: String? = signingKey?.trim()?.takeIf { it.isNotEmpty() }

    private val publicKey: PublicKey? = configuredKey?.let { encoded ->
        runCatching {
            KeyFactory.getInstance(SIGNATURE_ALGORITHM)
                .generatePublic(X509EncodedKeySpec(Base64.getMimeDecoder().decode(encoded)))
        }.getOrNull()
    }

    sealed interface Result {
        data class Staged(val stagedAs: File, val version: Version) : Result

        data class Failed(val reason: String) : Result
    }

    fun stage(asset: ReleaseAsset, version: Version, updateFolder: File, runningJarName: String): Result {
        if (configuredKey != null && publicKey == null) {
            return Result.Failed("updates.signing-key is not a base64 Ed25519 public key, so nothing can be verified")
        }
        val checksumUrl = asset.sha256Url
            ?: return Result.Failed(
                "release $version published no ${asset.name}.sha256, so the download cannot be " +
                    "verified; install it by hand if you trust it",
            )
        val signatureUrl = if (publicKey == null) {
            null
        } else {
            asset.signatureUrl
                ?: return Result.Failed(
                    "updates.signing-key is set and release $version published no ${asset.name}.sig",
                )
        }

        if (!updateFolder.isDirectory && !updateFolder.mkdirs()) {
            return Result.Failed("could not create the update folder ${updateFolder.path}")
        }

        val expected = fetchDigest(checksumUrl) ?: return Result.Failed("checksum could not be read")
        val signature = signatureUrl?.let {
            fetchSignature(it) ?: return Result.Failed("signature could not be read")
        }

        val temp = File(updateFolder, "$runningJarName.part")
        temp.delete()

        val digest = runCatching { download(asset.url, temp) }
            .getOrElse {
                temp.delete()
                return Result.Failed("download failed: ${it.message}")
            }

        if (!expected.equals(digest, ignoreCase = true)) {
            temp.delete()
            return Result.Failed("checksum mismatch: expected $expected, got $digest")
        }

        if (publicKey != null && signature != null && !verifySignature(temp, signature)) {
            temp.delete()
            return Result.Failed("the download is not signed by the configured key")
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

    private fun fetchSignature(url: String): ByteArray? = runCatching {
        var location = url
        repeat(MAX_REDIRECTS) {
            val response = http.send(request(location), HttpResponse.BodyHandlers.ofString())
            when (response.statusCode()) {
                200 -> return@runCatching Base64.getMimeDecoder().decode(response.body().trim())
                301, 302, 303, 307, 308 -> {
                    val next = response.headers().firstValue("Location").orElse(null) ?: return@runCatching null
                    location = URI.create(location).resolve(next).toString()
                }
                else -> return@runCatching null
            }
        }
        null
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun verifySignature(file: File, signature: ByteArray): Boolean = runCatching {
        val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
        verifier.initVerify(publicKey)
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                verifier.update(buffer, 0, read)
            }
        }
        verifier.verify(signature)
    }.getOrDefault(false)

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
        const val SIGNATURE_ALGORITHM = "Ed25519"

        val ALLOWED_HOSTS = listOf("github.com", "githubusercontent.com")

        const val MAX_BYTES = 200L * 1024 * 1024

        private const val MAX_REDIRECTS = 5

        private val DOWNLOAD_TIMEOUT: Duration = Duration.ofMinutes(5)
    }
}

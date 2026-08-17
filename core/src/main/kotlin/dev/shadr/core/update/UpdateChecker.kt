/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class UpdateChecker(
    private val repo: String,
    private val current: Version,
    private val channel: UpdateChannel = UpdateChannel.AUTO,
    private val assetPattern: Regex = DEFAULT_ASSET_PATTERN,
    private val http: HttpClient = defaultClient(),
    private val apiRoot: String = API_ROOT,
) {
    private var etag: String? = null
    private var cached: UpdateStatus? = null

    fun check(): UpdateStatus {
        val request = HttpRequest.newBuilder(URI.create("$apiRoot/repos/$repo/releases?per_page=$PAGE_SIZE"))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "shadr/$current")
            .apply { etag?.let { header("If-None-Match", it) } }
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build()

        val response = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }
            .getOrElse { return UpdateStatus.Failed("could not reach GitHub: ${it.message}") }

        return when (response.statusCode()) {
            304 -> cached ?: UpdateStatus.UpToDate(current)
            200 -> {
                response.headers().firstValue("ETag").ifPresent { etag = it }
                evaluate(response.body()).also { cached = it }
            }
            403, 429 -> UpdateStatus.Failed("GitHub rate limit reached; the next check will retry")
            404 -> UpdateStatus.Failed("no such repository: $repo")
            else -> UpdateStatus.Failed("GitHub answered ${response.statusCode()}")
        }
    }

    private fun evaluate(body: String): UpdateStatus {
        val releases = runCatching { json.decodeFromString<List<GhRelease>>(body) }
            .getOrElse { return UpdateStatus.Failed("could not read GitHub's answer: ${it.message}") }

        val candidate = releases
            .asSequence()
            .filterNot { it.draft }
            .mapNotNull { release -> Version.parse(release.tag)?.let { release to it } }
            .filter { (release, version) -> channel.accepts(version, release.prerelease, current) }
            .maxByOrNull { (_, version) -> version }
            ?: return UpdateStatus.UpToDate(current)

        val (release, version) = candidate
        if (version <= current) return UpdateStatus.UpToDate(current)

        val asset = release.assets.firstOrNull { assetPattern.matches(it.name) }
            ?: return UpdateStatus.Available(version, release.url, null, release.body.orEmpty())

        return UpdateStatus.Available(
            version = version,
            releaseUrl = release.url,
            asset = ReleaseAsset(
                name = asset.name,
                size = asset.size,
                url = asset.downloadUrl,
                sha256Url = release.assets.firstOrNull { it.name == "${asset.name}.sha256" }?.downloadUrl,
            ),
            notes = release.body.orEmpty(),
        )
    }

    companion object {
        const val API_ROOT = "https://api.github.com"

        val DEFAULT_ASSET_PATTERN = Regex("""shadr-paper-.*\.jar""")

        private const val PAGE_SIZE = 30

        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)

        private val json = Json { ignoreUnknownKeys = true }

        fun defaultClient(): HttpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build()
    }
}

enum class UpdateChannel {
    AUTO,
    STABLE,
    PRERELEASE;

    fun accepts(version: Version, markedPrerelease: Boolean, current: Version): Boolean {
        val isPre = markedPrerelease || version.isPrerelease
        return when (this) {
            STABLE -> !isPre
            PRERELEASE -> true
            AUTO -> !isPre || current.isPrerelease
        }
    }
}

sealed interface UpdateStatus {
    data class UpToDate(val current: Version) : UpdateStatus

    data class Available(
        val version: Version,
        val releaseUrl: String,
        val asset: ReleaseAsset?,
        val notes: String,
    ) : UpdateStatus

    data class Failed(val reason: String) : UpdateStatus
}

data class ReleaseAsset(
    val name: String,
    val size: Long,
    val url: String,
    val sha256Url: String?,
)

@Serializable
private data class GhRelease(
    @SerialName("tag_name") val tag: String = "",
    @SerialName("html_url") val url: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val body: String? = null,
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
private data class GhAsset(
    val name: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url") val downloadUrl: String = "",
)

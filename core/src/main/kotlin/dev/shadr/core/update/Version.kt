/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.core.update

data class Version(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: List<String> = emptyList(),
    val build: String = "",
) : Comparable<Version> {

    val isPrerelease: Boolean get() = prerelease.isNotEmpty()

    override fun compareTo(other: Version): Int {
        (major - other.major).let { if (it != 0) return it }
        (minor - other.minor).let { if (it != 0) return it }
        (patch - other.patch).let { if (it != 0) return it }

        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
        if (prerelease.isEmpty()) return 1
        if (other.prerelease.isEmpty()) return -1

        for (i in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val cmp = compareIdentifier(prerelease[i], other.prerelease[i])
            if (cmp != 0) return cmp
        }
        return prerelease.size - other.prerelease.size
    }

    override fun toString(): String = buildString {
        append("$major.$minor.$patch")
        if (prerelease.isNotEmpty()) append("-").append(prerelease.joinToString("."))
        if (build.isNotEmpty()) append("+").append(build)
    }

    companion object {
        val UNKNOWN = Version(0, 0, 0, listOf("unknown"))

        fun parse(raw: String?): Version? {
            val text = raw?.trim()?.removePrefix("v")?.removePrefix("V").orEmpty()
            if (text.isEmpty()) return null

            val build = text.substringAfter('+', "")
            val withoutBuild = text.substringBefore('+')
            val prerelease = withoutBuild.substringAfter('-', "")
                .split('.')
                .filter { it.isNotEmpty() }
            val core = withoutBuild.substringBefore('-').split('.')

            val numbers = core.map { it.toIntOrNull() ?: return null }
            if (numbers.isEmpty() || numbers.size > 3) return null

            return Version(
                major = numbers[0],
                minor = numbers.getOrElse(1) { 0 },
                patch = numbers.getOrElse(2) { 0 },
                prerelease = prerelease,
                build = build,
            )
        }

        private fun compareIdentifier(a: String, b: String): Int {
            val na = a.toIntOrNull()
            val nb = b.toIntOrNull()
            return when {
                na != null && nb != null -> na.compareTo(nb)
                na != null -> -1
                nb != null -> 1
                else -> a.compareTo(b)
            }
        }
    }
}

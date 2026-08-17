/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.core.page

import dev.shadr.core.Interpolation
import dev.shadr.core.text.Glyphs
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File

// I am not proud of this one.
class PageLoader(
    private val pagesDir: File,
    private val componentsDir: File,
    private val effectsDir: File,
) {
    private val yaml: Yaml
        get() = Yaml(SafeConstructor(LoaderOptions().apply { isAllowDuplicateKeys = false }))

    val issues = mutableListOf<String>()

    fun loadComponents(): Map<String, Map<String, Any?>> =
        ymlFiles(componentsDir).mapNotNull { file ->
            readMap(file)?.let { file.nameWithoutExtension to it }
        }.toMap()

    fun loadEffects(): Map<String, EffectDef> =
        ymlFiles(effectsDir).mapNotNull { file ->
            readMap(file)?.let { map ->
                val node = Node(map)
                val id = node.string("id") ?: file.nameWithoutExtension
                id to EffectDef(
                    id = id,
                    name = node.string("name") ?: id,
                    moveX = node.number("move-x", "moveX"),
                    moveY = node.number("move-y", "moveY"),
                    scaleXPercent = percent(node.string("scale-x", "scaleX")),
                    scaleYPercent = percent(node.string("scale-y", "scaleY")),
                    opacityDelta = node.int("opacity-delta", "opacityDelta"),
                    rotationDeg = node.number("rotation-deg", "rotationDeg"),
                    durationMs = node.number("duration-ms", "durationMs", fallback = 250.0).toLong(),
                    interpolation = Interpolation.parse(node.string("interpolation")),
                )
            }
        }.toMap()

    fun loadPages(components: Map<String, Map<String, Any?>> = loadComponents()): Map<String, Page> =
        ymlFiles(pagesDir).mapNotNull { file ->
            loadPage(file, components)?.let { it.name to it }
        }.toMap()

    fun loadPage(file: File, components: Map<String, Map<String, Any?>> = loadComponents()): Page? {
        val map = readMap(file) ?: return null
        val node = Node(map)
        val screen = readScreen(node.child("screen"))
        val resolver = TemplateResolver(components)
        val elements = resolver.resolve(node.list("blocks"), screen)
        resolver.issues.forEach { issues += "${file.name}: $it" }
        return Page(
            name = node.string("name") ?: file.nameWithoutExtension,
            screen = screen,
            elements = elements,
            animations = readAnimations(node),
        )
    }

    fun loadComponentAsPage(file: File, components: Map<String, Map<String, Any?>> = loadComponents()): Page? {
        val map = readMap(file) ?: return null
        val node = Node(map)
        val screen = ScreenDef()
        val params = (map["params"] as? Map<*, *>)?.stringKeyed() ?: emptyMap()

        val resolver = TemplateResolver(components + mapOf(SYNTHETIC to mapOf("params" to params, "blocks" to node.list("blocks"))))
        val elements = resolver.resolve(
            listOf(mapOf("type" to "component", "component" to SYNTHETIC)),
            screen,
        )
        resolver.issues.forEach { issues += "${file.name}: $it" }

        return Page(
            name = node.string("name") ?: file.nameWithoutExtension,
            screen = screen,
            elements = elements.map { it.copy(componentName = null, sourcePath = unwrap(it.sourcePath)) },
        )
    }

    private fun unwrap(path: String): String {
        val marker = "component:$SYNTHETIC."
        val index = path.indexOf(marker)
        return if (index < 0) path else path.substring(index + marker.length)
    }

    private fun readScreen(node: Node?): ScreenDef {
        if (node == null) return ScreenDef()
        val width = node.number("width", fallback = 1920.0)
        val height = node.number("height", fallback = 1080.0)
        return ScreenDef(
            width = width,
            height = height,
            offsetX = node.number("offsetX"),
            offsetY = node.number("offsetY"),
            previewDefaultZoom = node.number("preview.defaultZoom", fallback = 0.8),
            hitboxOffsetX = node.number("hitboxOffsetX"),
            hitboxOffsetY = node.number("hitboxOffsetY"),
            cursorSize = node.number("cursorSize", fallback = 10.0),
            cursorSpeed = node.number("cursorSpeed", fallback = 1.0),
            cursorUnicode = node.string("cursorUnicode") ?: Glyphs.CURSOR.toString(),
            cursorLayer = node.number("cursorLayer", fallback = 9700.0),
        )
    }

    private fun readAnimations(node: Node): List<GuiAnimationDef> {
        val out = mutableListOf<GuiAnimationDef>()
        for (raw in node.list("animations")) {
            val anim = raw.asNode() ?: continue
            val name = anim.string("name") ?: continue
            out += GuiAnimationDef(
                name = name,
                durationTicks = anim.int("durationTicks", "duration", fallback = 20),
                steps = anim.list("steps").mapNotNull { readStep(it) },
            )
        }
        return out
    }

    private fun readStep(raw: Any?): AnimationStep? {
        val node = raw.asNode() ?: return null
        val target = node.string("target") ?: return null
        return AnimationStep(
            target = target,
            axis = node.string("axis") ?: "y",
            easing = Interpolation.parse(node.string("easing", "interpolation")),
            from = node.number("from"),
            to = node.number("to"),
            values = node.list("values").mapNotNull { Expr.eval(it) },
            delayTicks = node.int("delay", "delayTicks"),
            durationTicks = node.int("duration", "durationTicks"),
        )
    }

    private fun percent(raw: String?): Double {
        val text = raw?.trim()?.removeSuffix("%") ?: return 0.0
        return Expr.eval(text) ?: 0.0
    }

    private companion object {
        const val SYNTHETIC = "__shadr_self"
    }

    private fun ymlFiles(dir: File): List<File> =
        dir.takeIf { it.isDirectory }
            ?.listFiles { f -> f.isFile && (f.extension == "yml" || f.extension == "yaml") }
            ?.sortedBy { it.name }
            ?: emptyList()

    private fun readMap(file: File): Map<String, Any?>? = try {
        file.inputStream().use { yaml.load<Any?>(it) }.let { (it as? Map<*, *>)?.stringKeyed() }
    } catch (e: Exception) {
        issues += "${file.name}: ${e.message}"
        null
    }
}

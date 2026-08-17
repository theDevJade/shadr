/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.page

import dev.shadr.core.HudAlignment
import dev.shadr.core.Rgb
import dev.shadr.core.RoundingSize
import dev.shadr.core.TextAlignment
import dev.shadr.core.text.Glyphs

class TemplateResolver(
    private val components: Map<String, Map<String, Any?>> = emptyMap(),
    private val maxDepth: Int = 12,
    private val maxLoopIterations: Int = 2048,
    private val maxElements: Int = 500,
) {
    private val placeholder = Regex("""\$\{([a-zA-Z0-9_.-]+)}|\{\{([a-zA-Z0-9_.-]+)}}""")

    val issues = mutableListOf<String>()

    fun resolve(blocks: List<Any?>, screen: ScreenDef): List<Element> {
        issues.clear()
        val out = mutableListOf<Element>()
        val vars = Expr.screenVars(screen.width, screen.height)
        expand(blocks, Ctx(params = emptyMap(), offsetX = 0.0, offsetY = 0.0, depth = 0, path = "", vars = vars), out)
        if (out.size > maxElements) {
            issues += "page exceeds max-page-elements ($maxElements). ${out.size - maxElements} element(s) dropped"
            return out.take(maxElements)
        }
        return out
    }

    private data class Ctx(
        val params: Map<String, Any?>,
        val offsetX: Double,
        val offsetY: Double,
        val depth: Int,
        val path: String,
        val vars: Map<String, Double>,
        val componentName: String? = null,
        val inheritedAlignment: HudAlignment? = null,
    )

    private fun expand(blocks: List<Any?>, ctx: Ctx, out: MutableList<Element>) {
        if (ctx.depth > maxDepth) {
            issues += "max component/children depth ($maxDepth) exceeded at '${ctx.path}'"
            return
        }
        blocks.forEachIndexed { index, block ->
            if (out.size >= maxElements) return
            expandOne(block, ctx.copy(path = appendPath(ctx.path, index.toString())), out)
        }
    }

    private fun expandOne(block: Any?, ctx: Ctx, out: MutableList<Element>) {
        val node = block.asNode(ctx.vars) ?: return

        if (node.has("loop")) return expandLoop(node, ctx, out)

        val resolved = Node(substituteMap(node.raw, ctx.params), ctx.vars)
        if (!resolved.bool("enabled", fallback = true)) return

        when (ElementType.parse(resolved.string("type"))) {
            ElementType.COMPONENT -> expandComponent(resolved, ctx, out)
            ElementType.GRID -> expandGrid(resolved, ctx, out)
            else -> {
                val element = readElement(resolved, ctx)
                if (element != null) out += element
                expandChildren(resolved, ctx, element, out)
            }
        }
    }

    private fun expandChildren(node: Node, ctx: Ctx, parent: Element?, out: MutableList<Element>) {
        val children = node.list("children")
        if (children.isEmpty()) return
        val childCtx = ctx.copy(
            offsetX = parent?.x ?: ctx.offsetX,
            offsetY = parent?.y ?: ctx.offsetY,
            depth = ctx.depth + 1,
            path = appendPath(ctx.path, "children"),
            inheritedAlignment = parent?.hudAlignment ?: ctx.inheritedAlignment,
        )
        expand(children, childCtx, out)
    }

    private fun expandComponent(node: Node, ctx: Ctx, out: MutableList<Element>) {
        val name = node.string("component", "name", "id")?.trim()
        if (name.isNullOrBlank()) {
            issues += "component invocation at '${ctx.path}' has no name"
            return
        }
        val definition = components[name]
        if (definition == null) {
            issues += "unknown component '$name' at '${ctx.path}'"
            return
        }
        val defaults = (definition["params"] as? Map<*, *>)?.stringKeyed() ?: emptyMap()
        val passed = (node["params"] as? Map<*, *>)?.stringKeyed() ?: emptyMap()
        val inline = node.raw.filterKeys { it !in RESERVED_INVOCATION_KEYS }
        val params = defaults + ctx.params.filterKeys { defaults.containsKey(it) } + inline + passed

        val blocks = (definition["blocks"] as? List<*>)?.toList() ?: emptyList()
        expand(
            blocks,
            ctx.copy(
                params = params,
                offsetX = ctx.offsetX + node.number("position.x", "x"),
                offsetY = ctx.offsetY + node.number("position.y", "y"),
                depth = ctx.depth + 1,
                path = appendPath(ctx.path, "component:$name"),
                componentName = name,
            ),
            out,
        )
    }

    private fun expandGrid(node: Node, ctx: Ctx, out: MutableList<Element>) {
        val gap = node.number("gap", fallback = 0.0)
        val row = !node.string("direction").equals("column", ignoreCase = true)
        val originX = ctx.offsetX + node.number("position.x", "x")
        val originY = ctx.offsetY + node.number("position.y", "y")

        var cursor = 0.0
        node.list("children").forEachIndexed { index, child ->
            val childX = originX + if (row) cursor else 0.0
            val childY = originY + if (row) 0.0 else cursor
            val childCtx = ctx.copy(
                offsetX = childX,
                offsetY = childY,
                depth = ctx.depth + 1,
                path = appendPath(ctx.path, "grid/$index"),
            )

            val before = out.size
            expandOne(child, childCtx, out)
            val produced = out.subList(before, out.size)

            val extent = when {
                produced.isEmpty() -> 0.0
                row -> (produced.maxOf { it.x + it.width } - childX).coerceAtLeast(0.0)
                else -> (produced.maxOf { it.y + it.height } - childY).coerceAtLeast(0.0)
            }
            cursor += extent + gap
        }
    }

    private fun expandLoop(node: Node, ctx: Ctx, out: MutableList<Element>) {
        val values = loopValues(node["loop"], ctx)
        if (values.isEmpty()) return
        val templates = node.list("block").ifEmpty { node.list("blocks") }.ifEmpty {
            listOf(node.raw.filterKeys { it != "loop" })
        }
        values.forEachIndexed { index, value ->
            templates.forEachIndexed { tIndex, template ->
                expandOne(
                    template,
                    ctx.copy(
                        params = ctx.params + mapOf("loop" to value, "loopIndex" to index, "loopNumber" to index + 1),
                        depth = ctx.depth + 1,
                        path = appendPath(ctx.path, "loop/$index/$tIndex"),
                        vars = ctx.vars + mapOf(
                            "loopIndex" to index.toDouble(),
                            "loopNumber" to (index + 1).toDouble(),
                        ) + (Expr.eval(value)?.let { mapOf("loop" to it) } ?: emptyMap()),
                    ),
                    out,
                )
            }
        }
    }

    private fun loopValues(spec: Any?, ctx: Ctx): List<Any?> = when (spec) {
        null -> emptyList()
        is List<*> -> spec.take(maxLoopIterations)
        is Map<*, *> -> {
            val node = Node(spec.stringKeyed(), ctx.vars)
            val from = node.int("from", fallback = 0)
            val to = node.int("to", "count", fallback = from - 1)
            if (to < from) emptyList() else (from..minOf(to, from + maxLoopIterations - 1)).toList()
        }
        else -> {
            val count = Expr.eval(spec, ctx.vars)?.toInt() ?: 0
            if (count <= 0) emptyList() else (0 until minOf(count, maxLoopIterations)).toList()
        }
    }

    private fun readElement(node: Node, ctx: Ctx): Element? {
        val type = ElementType.parse(node.string("type"))
        val x = ctx.offsetX + node.number("position.x", "x")
        val y = ctx.offsetY + node.number("position.y", "y")
        val rawWidth = node.number("size.width", "width", "scale.width", fallback = 20.0)
        val rawHeight = node.number("size.height", "height", "scale.height", fallback = 20.0)

        val mirrorX = rawWidth < 0.0
        val mirrorY = rawHeight < 0.0
        val width = maxOf(1.0, kotlin.math.abs(rawWidth))
        val height = maxOf(1.0, kotlin.math.abs(rawHeight))

        val color = Rgb.parse(node.string("color", "style.color")) ?: Rgb.WHITE
        val hudAlignment = parseHudAlignment(node.string("align", "hudAlign")) ?: ctx.inheritedAlignment
        ?: HudAlignment.CENTER

        val text = if (type.isTextual) {
            node.string("text") ?: ""
        } else {
            node.string("unicode", "text") ?: type.defaultGlyph.toString()
        }

        return Element(
            id = node.string("id") ?: autoId(ctx.path),
            type = type,
            x = x,
            y = y,
            width = width,
            height = height,
            layer = node.number("layer", "size.depth", "depth"),
            color = color,
            opacity = node.int("opacity", fallback = 255).coerceIn(0, 255),
            unicode = if (type.isTextual) Glyphs.BACKGROUND.toString() else text,
            text = if (type.isTextual) text else "",
            font = node.string("font", "style.font", "text.font") ?: Glyphs.FONT_UI,
            hudAlignment = hudAlignment,
            textAlignment = parseTextAlignment(node.string("textAlign", "text-align")) ?: TextAlignment.CENTER,
            lineWidth = node.int("lineWidth", "line-width", "wrap", fallback = 200).coerceAtLeast(1),
            enabled = true,
            outline = readOutline(node),
            rounding = when {
                !type.supportsRounding -> null
                node["rounding"] != null -> readRounding(node)
                type.roundedByDefault -> readRounding(node)
                else -> null
            },
            rotationDeg = node.number("rotationDeg", "rotation", "rotate"),
            pivotOffsetX = node.number("pivot.x", "pivotOffsetX"),
            pivotOffsetY = node.number("pivot.y", "pivotOffsetY"),
            mirrorX = mirrorX || node.bool("mirrorX"),
            mirrorY = mirrorY || node.bool("mirrorY"),
            item = node.string("shader").takeIf { type == ElementType.SHADER }
                ?: node.string("video").takeIf { type == ElementType.VIDEO }
                ?: node.string("item", "itemDisplay", "itemDisplayBlock"),
            itemCustomModelData = node.string("customModelData", "item.customModelData")?.toIntOrNull(),
            playerHeadText = node.bool("playerHeadText"),
            interaction = readInteraction(node),
            dynamic = readDynamicFields(node),
            sourcePath = ctx.path,
            originX = ctx.offsetX,
            originY = ctx.offsetY,
            componentName = ctx.componentName,
        )
    }

    private fun readOutline(node: Node): Outline? {
        val size = node.number("outline.size", fallback = 0.0)
        if (size <= 0.0) return null
        return Outline(
            size = size,
            color = Rgb.parse(node.string("outline.color")) ?: Rgb.WHITE,
            layer = node["outline.layer"]?.let { Expr.eval(it, node.vars) },
        )
    }

    private fun readRounding(node: Node): Rounding {
        fun corner(prefix: String) = CornerSpec(
            unicode = node.string("rounding.$prefix.unicode"),
            offsetX = node.number("rounding.$prefix.x", "rounding.$prefix.offsetX"),
            offsetY = node.number("rounding.$prefix.y", "rounding.$prefix.offsetY"),
        )
        return Rounding(
            size = RoundingSize.parse(node.string("rounding.size")).takeIf { it != RoundingSize.NONE }
                ?: RoundingSize.REGULAR,
            radius = node["rounding.radius"]?.let { Expr.eval(it, node.vars) },
            unicode = node.string("rounding.unicode"),
            topLeft = corner("topLeft"),
            topRight = corner("topRight"),
            bottomRight = corner("bottomRight"),
            bottomLeft = corner("bottomLeft"),
        )
    }

    private fun readInteraction(node: Node) = Interaction(
        interactive = node.bool("interactive", fallback = true),
        disableHitbox = node.bool("disableHitbox", "disable-hitbox"),
        hitboxOffsetX = node.number("hitboxOffsetX", "hitbox.x"),
        hitboxOffsetY = node.number("hitboxOffsetY", "hitbox.y"),
        hoverText = node.string("hoverText", "hover_text"),
        hoverEffect = node.string("hoverEffect", "hover.effect"),
        clickEffect = node.string("clickEffect", "click.effect"),
        onClick = readActions(node, "onClickAction", "onClick", "actions"),
        onLeftClick = readActions(node, "onLeftClickAction", "onLeftClick"),
        onRightClick = readActions(node, "onRightClickAction", "onRightClick"),
        permission = node.string("permission"),
    )

    private fun readActions(node: Node, vararg paths: String): List<ActionSpec> {
        for (path in paths) {
            when (val raw = node[path]) {
                null -> continue
                is List<*> -> return raw.mapNotNull { parseAction(it) }
                else -> parseAction(raw)?.let { return listOf(it) }
            }
        }
        return emptyList()
    }

    private fun parseAction(raw: Any?): ActionSpec? = when (raw) {
        null -> null
        is Map<*, *> -> raw.entries.firstOrNull()
            ?.let { ActionSpec(it.key.toString().trim().lowercase(), it.value?.toString().orEmpty()) }
        else -> {
            val text = raw.toString().trim()
            val split = text.indexOf(':')
            if (split <= 0) null
            else ActionSpec(text.substring(0, split).trim().lowercase(), text.substring(split + 1).trim())
        }
    }

    private fun readDynamicFields(node: Node): DynamicFields {
        val dynamic = linkedMapOf<String, String>()
        for ((path, aliases) in DYNAMIC_FIELDS) {
            val raw = (listOf(path) + aliases).firstNotNullOfOrNull { node[it] }?.toString() ?: continue
            if (PlaceholderScanner.hasPlaceholder(raw)) dynamic[path] = raw
        }
        return dynamic
    }

    private fun substituteMap(map: Map<String, Any?>, params: Map<String, Any?>): Map<String, Any?> {
        if (params.isEmpty()) return map
        return map.mapValues { (key, value) ->
            if (key in DEFERRED_KEYS) value else substituteValue(value, params)
        }
    }

    private fun substituteValue(value: Any?, params: Map<String, Any?>): Any? = when (value) {
        null -> null
        is String -> substituteString(value, params)
        is List<*> -> value.map { substituteValue(it, params) }
        is Map<*, *> -> value.stringKeyed().mapValues { (k, v) ->
            if (k in DEFERRED_KEYS) v else substituteValue(v, params)
        }
        else -> value
    }

    private fun substituteString(text: String, params: Map<String, Any?>): Any? {
        val whole = placeholder.matchEntire(text)
        if (whole != null) {
            val key = whole.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return text
            return params[key] ?: ""
        }
        if (!text.contains("\${") && !text.contains("{{")) return text
        return placeholder.replace(text) { match ->
            val key = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return@replace match.value
            params[key]?.toString() ?: ""
        }
    }

    private fun parseHudAlignment(raw: String?): HudAlignment? = when (raw?.trim()?.lowercase()) {
        "left" -> HudAlignment.LEFT
        "right" -> HudAlignment.RIGHT
        "center", "centre", "middle" -> HudAlignment.CENTER
        else -> null
    }

    private fun parseTextAlignment(raw: String?): TextAlignment? = when (raw?.trim()?.lowercase()) {
        "left" -> TextAlignment.LEFT
        "right" -> TextAlignment.RIGHT
        "center", "centre", "middle" -> TextAlignment.CENTER
        else -> null
    }

    private fun appendPath(base: String, segment: String) = if (base.isEmpty()) segment else "$base.$segment"

    private fun autoId(path: String) = "el_" + path.replace('.', '_').replace('/', '_')

    private companion object {
        val DEFERRED_KEYS = setOf("children", "block", "blocks")

        val RESERVED_INVOCATION_KEYS = setOf("type", "component", "params", "position", "children", "enabled")

        val DYNAMIC_FIELDS = mapOf(
            "size.width" to listOf("width", "scale.width"),
            "size.height" to listOf("height", "scale.height"),
            "opacity" to emptyList(),
        )
    }
}

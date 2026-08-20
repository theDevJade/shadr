/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.editor

import dev.shadr.core.page.Element
import dev.shadr.core.page.Page
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.composer.Composer
import org.yaml.snakeyaml.emitter.Emitter
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.NodeId
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.NodeTuple
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.parser.ParserImpl
import org.yaml.snakeyaml.reader.StreamReader
import org.yaml.snakeyaml.resolver.Resolver
import org.yaml.snakeyaml.serializer.Serializer
import java.io.File
import java.io.StringWriter

class PageWriter {
    data class SaveResult(
        val saved: Int,
        val skipped: Map<String, String>,
        val expressionsReplaced: List<String>,
        val assignedPaths: Map<String, String> = emptyMap(),
    ) {
        val ok: Boolean get() = skipped.isEmpty()
    }

    fun save(file: File, original: Page, edited: Page): SaveResult {
        val loader = LoaderOptions().apply { isProcessComments = true }
        val root = Composer(ParserImpl(StreamReader(file.readText()), loader), Resolver(), loader)
            .singleNode as? MappingNode
            ?: return SaveResult(0, mapOf("*" to "page root is not a mapping"), emptyList())

        val blocks = root.get("blocks") as? SequenceNode
            ?: return SaveResult(0, mapOf("*" to "page has no blocks sequence"), emptyList())

        val before = original.elements.associateBy { it.id }
        val skipped = linkedMapOf<String, String>()
        val replaced = mutableListOf<String>()
        val assigned = linkedMapOf<String, String>()
        val componentMoves =
            linkedMapOf<String, MutableList<Triple<Element, Element, Map<String, Value>>>>()
        var saved = 0

        for (element in edited.elements) {
            val previous = before[element.id]
            if (previous == null) {
                blocks.value.add(elementToNode(element))
                assigned[element.id] = (blocks.value.size - 1).toString()
                saved++
                continue
            }
            val changes = diff(previous, element)
            if (changes.isEmpty()) continue

            if (element.componentName != null) {
                componentMoves
                    .getOrPut(instancePathOf(element.sourcePath) ?: "") { mutableListOf() }
                    .add(Triple(previous, element, changes))
                continue
            }

            val reason = unaddressableReason(element)
            if (reason != null) {
                skipped[element.id] = reason
                continue
            }

            val node = navigate(blocks, element.sourcePath)
            if (node !is MappingNode) {
                skipped[element.id] = "source node no longer at '${element.sourcePath}'"
                continue
            }
            for ((path, value) in changes) {
                if (replacesExpression(node, path)) replaced += "${element.id}.$path"
                put(node, path, value)
            }
            saved++
        }

        saved += applyComponentMoves(blocks, componentMoves, skipped, replaced)

        val deletedIds = before.keys - edited.elements.map { it.id }.toSet()
        saved += remove(blocks, deletedIds.mapNotNull { before[it] }, skipped)

        if (original.screen != edited.screen) {
            writeScreen(root, original.screen, edited.screen, replaced)
            saved++
        }

        if (original.animations != edited.animations) {
            writeAnimations(root, original.animations, edited.animations, replaced)
            saved++
        }

        if (saved > 0) file.writeText(serialize(root))
        return SaveResult(saved, skipped, replaced, assigned)
    }

    private fun applyComponentMoves(
        blocks: SequenceNode,
        moves: Map<String, MutableList<Triple<Element, Element, Map<String, Value>>>>,
        skipped: MutableMap<String, String>,
        replaced: MutableList<String>,
    ): Int {
        var saved = 0
        for ((instancePath, entries) in moves) {
            val ids = entries.map { it.second.id }
            if (instancePath.isBlank()) {
                ids.forEach { skipped[it] = "component instance has no addressable path" }
                continue
            }

            val nonPositional = entries.flatMap { (_, _, changes) ->
                changes.keys.filterNot { it == "position.x" || it == "position.y" }
            }.distinct()
            if (nonPositional.isNotEmpty()) {
                ids.forEach {
                    skipped[it] = "only a move can be written back to component " +
                        "'${entries.first().second.componentName}'; ${nonPositional.joinToString()} " +
                        "must be changed in the component file"
                }
                continue
            }

            val deltas = entries.map { (from, to, _) -> (to.x - from.x) to (to.y - from.y) }.distinct()
            if (deltas.size > 1) {
                ids.forEach {
                    skipped[it] = "a component's parts cannot move independently; move the whole " +
                        "'${entries.first().second.componentName}' instead"
                }
                continue
            }

            val node = navigate(blocks, instancePath)
            if (node !is MappingNode) {
                ids.forEach { skipped[it] = "component instance no longer at '$instancePath'" }
                continue
            }

            val (dx, dy) = deltas.single()
            if (dx == 0.0 && dy == 0.0) continue

            for ((path, delta) in listOf("position.x" to dx, "position.y" to dy)) {
                if (delta == 0.0) continue
                if (replacesExpression(node, path)) replaced += "$instancePath.$path"
                put(node, path, Value.Num(currentNumber(node, path) + delta))
            }
            saved++
        }
        return saved
    }

    private fun currentNumber(node: MappingNode, path: String): Double {
        val scalar = resolve(node, path) as? ScalarNode ?: return 0.0
        return scalar.value.trim().toDoubleOrNull() ?: 0.0
    }

    private fun writeScreen(
        root: MappingNode,
        before: dev.shadr.core.page.ScreenDef,
        after: dev.shadr.core.page.ScreenDef,
        replaced: MutableList<String>,
    ) {
        val changes = linkedMapOf<String, Value>()
        if (before.width != after.width) changes["width"] = Value.Num(after.width)
        if (before.height != after.height) changes["height"] = Value.Num(after.height)
        if (before.offsetX != after.offsetX) changes["offsetX"] = Value.Num(after.offsetX)
        if (before.offsetY != after.offsetY) changes["offsetY"] = Value.Num(after.offsetY)
        if (before.hitboxOffsetX != after.hitboxOffsetX) {
            changes["hitboxOffsetX"] = Value.Num(after.hitboxOffsetX)
        }
        if (before.hitboxOffsetY != after.hitboxOffsetY) {
            changes["hitboxOffsetY"] = Value.Num(after.hitboxOffsetY)
        }
        if (before.cursorSize != after.cursorSize) changes["cursorSize"] = Value.Num(after.cursorSize)
        if (before.cursorSpeed != after.cursorSpeed) changes["cursorSpeed"] = Value.Num(after.cursorSpeed)
        if (before.cursorLayer != after.cursorLayer) changes["cursorLayer"] = Value.Num(after.cursorLayer)
        if (before.cursorUnicode != after.cursorUnicode) {
            changes["cursorUnicode"] = Value.Text(after.cursorUnicode)
        }
        if (before.previewDefaultZoom != after.previewDefaultZoom) {
            changes["preview.defaultZoom"] = Value.Num(after.previewDefaultZoom)
        }
        if (before.hud != after.hud) changes["hud"] = Value.Flag(after.hud)
        if (changes.isEmpty()) return

        val screen = (root.get("screen") as? MappingNode) ?: MappingNode(
            Tag.MAP,
            mutableListOf(),
            DumperOptions.FlowStyle.BLOCK,
        ).also { root.put("screen", it) }

        for ((path, value) in changes) {
            if (replacesExpression(screen, path)) replaced += "screen.$path"
            put(screen, path, value)
        }
    }

    private fun writeAnimations(
        root: MappingNode,
        before: List<dev.shadr.core.page.GuiAnimationDef>,
        after: List<dev.shadr.core.page.GuiAnimationDef>,
        replaced: MutableList<String>,
    ) {
        if (after.isEmpty()) {
            root.value.removeAll { (it.keyNode as? ScalarNode)?.value == "animations" }
            return
        }

        val existing = root.get("animations") as? SequenceNode
        if (existing == null || !uniquelyKeyed(existing.value) { animationKey(it) }) {
            root.put("animations", buildAnimations(after))
            return
        }

        val nodesByName = existing.value.mapNotNull { node ->
            (node as? MappingNode)?.let { animationKey(it)?.to(it) }
        }.toMap()
        val previousByName = before.groupBy { it.name }
            .filterValues { it.size == 1 }
            .mapValues { (_, found) -> found.single() }

        val ordered = mutableListOf<Node>()
        for (animation in after) {
            val node = nodesByName[animation.name]
            val previous = previousByName[animation.name]
            if (node == null || previous == null) {
                ordered += buildAnimation(animation)
                continue
            }
            if (previous.durationTicks != animation.durationTicks) {
                putChanged(
                    node, "durationTicks", Value.Num(animation.durationTicks.toDouble()),
                    replaced, animation.name,
                )
            }
            writeSteps(node, previous, animation, replaced)
            ordered += node
        }
        existing.value.clear()
        existing.value.addAll(ordered)
    }

    private fun writeSteps(
        node: MappingNode,
        before: dev.shadr.core.page.GuiAnimationDef,
        after: dev.shadr.core.page.GuiAnimationDef,
        replaced: MutableList<String>,
    ) {
        val existing = node.get("steps") as? SequenceNode
        if (existing == null || !uniquelyKeyed(existing.value) { stepKey(it) }) {
            node.put("steps", buildSteps(after))
            return
        }

        val nodesByKey = existing.value.mapNotNull { step ->
            (step as? MappingNode)?.let { stepKey(it)?.to(it) }
        }.toMap()
        val previousByKey = before.steps.groupBy { stepKey(it) }
            .filterValues { it.size == 1 }
            .mapValues { (_, found) -> found.single() }

        val ordered = mutableListOf<Node>()
        for (step in after.steps) {
            val key = stepKey(step)
            val stepNode = nodesByKey[key]
            val previous = previousByKey[key]
            if (stepNode == null || previous == null) {
                ordered += buildStep(step)
                continue
            }
            val where = "${after.name}.${step.target}.${step.axis}"
            if (previous.easing != step.easing) {
                putChanged(stepNode, "easing", Value.Text(step.easing.id), replaced, where)
            }
            if (step.values.isNotEmpty()) {
                if (previous.values != step.values) {
                    stepNode.value.removeAll { (it.keyNode as? ScalarNode)?.value in setOf("from", "to") }
                    stepNode.put(
                        "values",
                        SequenceNode(
                            Tag.SEQ,
                            step.values.map { scalar(Value.Num(it)) },
                            DumperOptions.FlowStyle.FLOW,
                        ),
                    )
                }
            } else {
                if (previous.values.isNotEmpty()) {
                    stepNode.value.removeAll { (it.keyNode as? ScalarNode)?.value == "values" }
                }
                if (previous.from != step.from) {
                    putChanged(stepNode, "from", Value.Num(step.from), replaced, where)
                }
                if (previous.to != step.to) {
                    putChanged(stepNode, "to", Value.Num(step.to), replaced, where)
                }
            }
            writeOptional(stepNode, "delay", previous.delayTicks, step.delayTicks, replaced, where)
            writeOptional(stepNode, "duration", previous.durationTicks, step.durationTicks, replaced, where)
            ordered += stepNode
        }
        existing.value.clear()
        existing.value.addAll(ordered)
    }

    private fun writeOptional(
        node: MappingNode,
        key: String,
        before: Int,
        after: Int,
        replaced: MutableList<String>,
        where: String,
    ) {
        if (before == after) return
        if (after == 0) node.value.removeAll { (it.keyNode as? ScalarNode)?.value == key }
        else putChanged(node, key, Value.Num(after.toDouble()), replaced, where)
    }

    private fun putChanged(
        node: MappingNode,
        key: String,
        value: Value,
        replaced: MutableList<String>,
        where: String,
    ) {
        val current = node.get(key) as? ScalarNode
        if (current != null && isExpression(current)) replaced += "$where.$key"
        node.put(key, scalar(value))
    }

    private fun isExpression(node: ScalarNode): Boolean =
        node.value.toDoubleOrNull() == null && node.value.any { it in "+-*/%()" }

    private fun uniquelyKeyed(nodes: List<Node>, key: (MappingNode) -> String?): Boolean {
        val keys = nodes.map { (it as? MappingNode)?.let(key) ?: return false }
        return keys.toSet().size == keys.size
    }

    private fun animationKey(node: MappingNode): String? =
        (node.get("name") as? ScalarNode)?.value

    private fun stepKey(step: dev.shadr.core.page.AnimationStep): String =
        "${step.target}\u0000${step.axis}"

    private fun stepKey(node: MappingNode): String? {
        val target = (node.get("target") as? ScalarNode)?.value ?: return null
        val axis = (node.get("axis") as? ScalarNode)?.value ?: return null
        return "$target\u0000$axis"
    }

    private fun buildAnimations(
        animations: List<dev.shadr.core.page.GuiAnimationDef>,
    ): SequenceNode = SequenceNode(
        Tag.SEQ,
        animations.map { buildAnimation(it) },
        DumperOptions.FlowStyle.BLOCK,
    )

    private fun buildAnimation(animation: dev.shadr.core.page.GuiAnimationDef): MappingNode {
        val node = MappingNode(Tag.MAP, mutableListOf(), DumperOptions.FlowStyle.BLOCK)
        node.put("name", scalar(Value.Text(animation.name)))
        node.put("durationTicks", scalar(Value.Num(animation.durationTicks.toDouble())))
        node.put("steps", buildSteps(animation))
        return node
    }

    private fun buildSteps(animation: dev.shadr.core.page.GuiAnimationDef): SequenceNode =
        SequenceNode(
            Tag.SEQ,
            animation.steps.map { buildStep(it) },
            DumperOptions.FlowStyle.BLOCK,
        )

    private fun buildStep(step: dev.shadr.core.page.AnimationStep): MappingNode {
        val stepNode = MappingNode(Tag.MAP, mutableListOf(), DumperOptions.FlowStyle.BLOCK)
        stepNode.put("target", scalar(Value.Text(step.target)))
        stepNode.put("axis", scalar(Value.Text(step.axis)))
        stepNode.put("easing", scalar(Value.Text(step.easing.id)))
        if (step.values.isNotEmpty()) {
            stepNode.put(
                "values",
                SequenceNode(
                    Tag.SEQ,
                    step.values.map { scalar(Value.Num(it)) },
                    DumperOptions.FlowStyle.FLOW,
                ),
            )
        } else {
            stepNode.put("from", scalar(Value.Num(step.from)))
            stepNode.put("to", scalar(Value.Num(step.to)))
        }
        if (step.delayTicks != 0) stepNode.put("delay", scalar(Value.Num(step.delayTicks.toDouble())))
        if (step.durationTicks != 0) {
            stepNode.put("duration", scalar(Value.Num(step.durationTicks.toDouble())))
        }
        return stepNode
    }

    private fun remove(
        blocks: SequenceNode,
        deleted: List<Element>,
        skipped: MutableMap<String, String>,
    ): Int {
        var removed = 0
        val ordered = deleted.sortedWith(
            compareByDescending<Element> { steps(it.sourcePath)?.size ?: 0 }
                .thenByDescending { steps(it.sourcePath)?.lastOrNull()?.toIntOrNull() ?: 0 },
        )
        for (element in ordered) {
            val reason = unaddressableReason(element)
            if (reason != null) {
                skipped[element.id] = reason
                continue
            }
            val path = element.sourcePath
            val steps = steps(path)
            if (steps == null) {
                skipped[element.id] = "source path '$path' addresses no single node"
                continue
            }
            val index = steps.last().toIntOrNull()
            if (index == null) {
                skipped[element.id] = "source path '$path' does not end in an index"
                continue
            }
            val parent = walk(blocks, steps.dropLast(1))
            if (parent !is SequenceNode || index !in parent.value.indices) {
                skipped[element.id] = "source node no longer at '$path'"
                continue
            }
            parent.value.removeAt(index)
            removed++
        }
        return removed
    }

    private fun walk(blocks: SequenceNode, steps: List<String>): Node? {
        var current: Node = blocks
        for (step in steps) {
            current = when (val index = step.toIntOrNull()) {
                null -> (current as? MappingNode)?.get(step)
                else -> (current as? SequenceNode)?.value?.getOrNull(index)
            } ?: return null
        }
        return current
    }

    private fun elementToNode(element: Element): MappingNode {
        val node = MappingNode(Tag.MAP, mutableListOf(), DumperOptions.FlowStyle.BLOCK)
        node.put("type", scalar(Value.Text(element.type.id)))
        node.put("id", scalar(Value.Text(element.id)))
        node.put("layer", scalar(Value.Num(element.layer)))
        node.put("color", scalar(Value.Text(element.color.hex())))
        if (element.opacity != 255) node.put("opacity", scalar(Value.Num(element.opacity.toDouble())))

        put(node, "position.x", Value.Num(element.x - element.originX))
        put(node, "position.y", Value.Num(element.y - element.originY))
        put(node, "size.width", Value.Num(element.width))
        put(node, "size.height", Value.Num(element.height))

        if (element.type.isTextual) node.put("text", scalar(Value.Text(element.text)))
        element.item?.takeIf { it.isNotBlank() }?.let {
            node.put(element.type.sourceKey, scalar(Value.Text(it)))
        }
        if (element.stream) node.put("stream", scalar(Value.Text("true")))
        element.rounding?.let { rounding ->
            put(node, "rounding.size", Value.Text(rounding.size.id))
            rounding.radius?.let { put(node, "rounding.radius", Value.Num(it)) }
        }
        element.outline?.let { outline ->
            put(node, "outline.size", Value.Num(outline.size))
            put(node, "outline.color", Value.Text(outline.color.hex()))
        }
        element.input?.let { input ->
            node.put("placeholder", scalar(Value.Text(input.placeholder)))
            node.put("maxLength", scalar(Value.Num(input.maxLength.toDouble())))
            if (input.lines != 1) node.put("lines", scalar(Value.Num(input.lines.toDouble())))
            if (input.secret) node.put("secret", scalar(Value.Text("true")))
        }
        return node
    }

    private fun unaddressableReason(element: Element) = Companion.unaddressableReason(element)

    private fun steps(path: String) = Companion.steps(path)

    private fun instancePathOf(path: String) = Companion.instancePathOf(path)

    private sealed interface Value {
        data class Num(val value: Double) : Value
        data class Text(val value: String) : Value
        data class Flag(val value: Boolean) : Value
        data class Actions(val value: List<dev.shadr.core.page.ActionSpec>) : Value
    }

    private fun diff(before: Element, after: Element): Map<String, Value> {
        val changes = linkedMapOf<String, Value>()
        if (before.x != after.x) changes["position.x"] = Value.Num(after.x - after.originX)
        if (before.y != after.y) changes["position.y"] = Value.Num(after.y - after.originY)
        if (before.width != after.width) changes["size.width"] = Value.Num(after.width)
        if (before.height != after.height) changes["size.height"] = Value.Num(after.height)
        if (before.layer != after.layer) changes["layer"] = Value.Num(after.layer)
        if (before.opacity != after.opacity) changes["opacity"] = Value.Num(after.opacity.toDouble())
        if (before.color != after.color) changes["color"] = Value.Text(after.color.hex())
        if (before.rotationDeg != after.rotationDeg) changes["rotationDeg"] = Value.Num(after.rotationDeg)
        if (before.text != after.text) changes["text"] = Value.Text(after.text)
        if (before.item != after.item) changes[after.type.sourceKey] = Value.Text(after.item.orEmpty())
        if (before.stream != after.stream) changes["stream"] = Value.Text(after.stream.toString())
        if (before.font != after.font) changes["font"] = Value.Text(after.font)
        if (before.enabled != after.enabled) changes["enabled"] = Value.Text(after.enabled.toString())
        if (before.type != after.type) changes["type"] = Value.Text(after.type.id)
        if (before.rounding?.size != after.rounding?.size) {
            after.rounding?.let { changes["rounding.size"] = Value.Text(it.size.id) }
        }
        if (before.rounding?.radius != after.rounding?.radius) {
            after.rounding?.radius?.let { changes["rounding.radius"] = Value.Num(it) }
        }
        if (before.outline?.size != after.outline?.size) {
            after.outline?.let { changes["outline.size"] = Value.Num(it.size) }
        }
        if (before.outline?.color != after.outline?.color) {
            after.outline?.let { changes["outline.color"] = Value.Text(it.color.hex()) }
        }
        diffInteraction(before.interaction, after.interaction, changes)
        return changes
    }

    private fun diffInteraction(
        before: dev.shadr.core.page.Interaction,
        after: dev.shadr.core.page.Interaction,
        changes: MutableMap<String, Value>,
    ) {
        if (before.interactive != after.interactive) {
            changes["interactive"] = Value.Text(after.interactive.toString())
        }
        if (before.disableHitbox != after.disableHitbox) {
            changes["disableHitbox"] = Value.Text(after.disableHitbox.toString())
        }
        if (before.hitboxOffsetX != after.hitboxOffsetX) {
            changes["hitboxOffsetX"] = Value.Num(after.hitboxOffsetX)
        }
        if (before.hitboxOffsetY != after.hitboxOffsetY) {
            changes["hitboxOffsetY"] = Value.Num(after.hitboxOffsetY)
        }
        for ((key, pair) in mapOf(
            "hoverText" to (before.hoverText to after.hoverText),
            "hoverEffect" to (before.hoverEffect to after.hoverEffect),
            "clickEffect" to (before.clickEffect to after.clickEffect),
            "permission" to (before.permission to after.permission),
        )) {
            val (was, now) = pair
            if (was != now) changes[key] = Value.Text(now.orEmpty())
        }
        for ((key, pair) in mapOf(
            "onClickAction" to (before.onClick to after.onClick),
            "onLeftClickAction" to (before.onLeftClick to after.onLeftClick),
            "onRightClickAction" to (before.onRightClick to after.onRightClick),
        )) {
            val (was, now) = pair
            if (was != now) changes[key] = Value.Actions(now)
        }
    }

    private fun replacesExpression(node: MappingNode, path: String): Boolean {
        val existing = resolve(node, path) as? ScalarNode ?: return false
        return existing.tag == Tag.STR && existing.value.toDoubleOrNull() == null &&
            existing.value.any { it in "+-*/%()" }
    }

    private fun navigate(blocks: SequenceNode, path: String): Node? {
        return walk(blocks, steps(path) ?: return null)
    }

    private fun resolve(node: MappingNode, path: String): Node? {
        var current: Node = node
        for (key in path.split('.')) {
            current = (current as? MappingNode)?.get(key) ?: return null
        }
        return current
    }

    private fun put(node: MappingNode, path: String, value: Value) {
        val keys = path.split('.')
        var current = node
        for (key in keys.dropLast(1)) {
            val existing = current.get(key)
            if (existing is MappingNode) {
                current = existing
            } else {
                val created = MappingNode(Tag.MAP, mutableListOf(), DumperOptions.FlowStyle.BLOCK)
                current.put(key, created)
                current = created
            }
        }
        val last = keys.last()
        if (value is Value.Actions) {
            if (value.value.isEmpty()) {
                current.value.removeAll { (it.keyNode as? ScalarNode)?.value == last }
            } else {
                current.put(last, actionSequence(value.value))
            }
            return
        }
        if (value is Value.Text && value.value.isEmpty()) {
            current.value.removeAll { (it.keyNode as? ScalarNode)?.value == last }
            return
        }
        current.put(last, scalar(value))
    }

    private fun actionSequence(actions: List<dev.shadr.core.page.ActionSpec>): SequenceNode =
        SequenceNode(
            Tag.SEQ,
            actions.map { action ->
                MappingNode(
                    Tag.MAP,
                    mutableListOf(
                        NodeTuple(scalar(Value.Text(action.verb)), scalar(Value.Text(action.argument))),
                    ),
                    DumperOptions.FlowStyle.BLOCK,
                )
            },
            DumperOptions.FlowStyle.BLOCK,
        )

    private fun scalar(value: Value): ScalarNode = when (value) {
        is Value.Actions -> error("an action list is not a scalar")
        is Value.Flag -> ScalarNode(
            Tag.BOOL,
            value.value.toString(),
            null,
            null,
            DumperOptions.ScalarStyle.PLAIN,
        )
        is Value.Num -> {
            val text = if (value.value == value.value.toLong().toDouble()) {
                value.value.toLong().toString()
            } else {
                value.value.toString()
            }
            ScalarNode(resolver.resolve(NodeId.scalar, text, true), text, null, null, DumperOptions.ScalarStyle.PLAIN)
        }
        is Value.Text -> {
            val inferred = resolver.resolve(NodeId.scalar, value.value, true)
            val style = if (inferred == Tag.STR) DumperOptions.ScalarStyle.PLAIN
            else DumperOptions.ScalarStyle.SINGLE_QUOTED
            ScalarNode(Tag.STR, value.value, null, null, style)
        }
    }

    private val resolver = Resolver()

    private fun serialize(root: Node): String {
        val options = DumperOptions().apply {
            isProcessComments = true
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            indent = 2
            indicatorIndent = 2
            setIndentWithIndicator(true)
            width = 100
        }
        val out = StringWriter()
        Serializer(Emitter(out, options), Resolver(), options, null).run {
            open()
            serialize(root)
            close()
        }
        return out.toString()
    }

    private fun MappingNode.get(key: String): Node? =
        value.firstOrNull { (it.keyNode as? ScalarNode)?.value == key }?.valueNode

    private fun MappingNode.put(key: String, node: Node) {
        val index = value.indexOfFirst { (it.keyNode as? ScalarNode)?.value == key }
        val tuple = NodeTuple(ScalarNode(Tag.STR, key, null, null, DumperOptions.ScalarStyle.PLAIN), node)
        if (index >= 0) {
            value[index] = tuple
        } else {
            value.add(tuple)
        }
    }

    companion object {
        fun instancePathOf(path: String): String? {
            val segments = path.split('.')
            val at = segments.indexOfFirst { it.startsWith("component:") }
            if (at <= 0) return null
            return segments.take(at).joinToString(".")
        }

        fun unaddressableReason(element: Element): String? {
            if (element.componentName != null) {
                return "from component '${element.componentName}'"
            }
            val path = element.sourcePath
            if (path.isBlank()) return "no source path"
            if (steps(path) != null) return null

            val segment = path.split('.').first { stepsFor(it) == null }
            val kind = segment.substringBefore('/').substringBefore(':')
            return "expanded from '$kind'"
        }

        internal fun steps(path: String): List<String>? {
            val out = mutableListOf<String>()
            for (segment in path.split('.')) {
                out += stepsFor(segment) ?: return null
            }
            return out
        }

        private fun stepsFor(segment: String): List<String>? = when {
            segment.toIntOrNull() != null -> listOf(segment)
            segment == "children" -> listOf("children")
            segment.startsWith("grid/") ->
                segment.removePrefix("grid/").toIntOrNull()?.let { listOf("children", it.toString()) }
            else -> null
        }
    }
}

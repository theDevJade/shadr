/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.core

import dev.shadr.core.shader.GlslComposer
import dev.shadr.core.shader.GlslHelpers
import dev.shadr.core.shader.GlslSymbols
import dev.shadr.core.shader.ShaderTint
import dev.shadr.core.shader.GlslValidator
import dev.shadr.core.shader.ShaderDef
import dev.shadr.core.shader.ShaderLoader
import dev.shadr.core.shader.ShaderRegistry
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShaderPipelineTest {
    private fun dir() = createTempDirectory("shadr-shaders").toFile()

    private fun write(dir: File, id: String, source: String) =
        File(dir, "$id.glsl").apply { writeText(source) }

    private val valid = """
        |// @description test
        |vec4 shadr_main(vec2 uv, float time, vec4 tint) { return vec4(uv, 0.0, 1.0); }
    """.trimMargin()

    @Test
    fun `a shader loads with its metadata`() {
        val dir = dir()
        write(dir, "aurora", "// @description A sweep\n// @preview 4cc9f0\n$valid")

        val registry = ShaderLoader(dir).load()
        val shader = registry["aurora"]
        assertNotNull(shader)
        assertEquals("A sweep", shader.description)
        assertEquals(0x4CC9F0, shader.previewTint.packed)
    }

    @Test
    fun `indices are stable under insertion`() {
        val dir = dir()
        write(dir, "beta", valid)
        write(dir, "delta", valid)
        val before = ShaderLoader(dir).load().shaders.associate { it.id to it.index }

        write(dir, "alpha", valid)
        val after = ShaderLoader(dir).load().shaders.associate { it.id to it.index }

        assertEquals(0, after["alpha"], "a new first-alphabetically shader should take index 0")

        assertEquals(before.keys + "alpha", after.keys)
        assertEquals(
            after.entries.sortedBy { it.key }.map { it.value },
            listOf(0, 1, 2),
            "indices must be dense and follow id order",
        )
    }

    @Test
    fun `an id that could not be a resource path is refused`() {
        val dir = dir()
        write(dir, "Not Valid", valid)
        val loader = ShaderLoader(dir)
        loader.load()
        assertTrue(loader.issues.any { it.contains("id must match") }, "${loader.issues}")
    }

    @Test
    fun `renaming refuses to overwrite, and validates the new name`() {
        val dir = dir()
        write(dir, "a", valid)
        write(dir, "b", valid)
        val loader = ShaderLoader(dir)

        assertNotNull(loader.rename("a", "b"), "renaming over an existing shader was allowed")
        assertNotNull(loader.rename("a", "Not Valid"), "an invalid id was accepted")
        assertNotNull(loader.rename("nope", "c"), "renaming a missing shader was allowed")

        assertEquals(setOf("a", "b"), ShaderLoader(dir).load().shaders.map { it.id }.toSet())

        assertEquals(null, loader.rename("a", "c"))
        assertEquals(setOf("b", "c"), ShaderLoader(dir).load().shaders.map { it.id }.toSet())
    }

    @Test
    fun `a version directive is refused, because it would be spliced mid-program`() {
        val problems = GlslValidator.validate(ShaderDef("t", "#version 330\n$valid"))
        assertTrue(problems.any { it.message.contains("#version") }, "$problems")
    }

    @Test
    fun `a missing entry point is named, rather than left to the linker`() {
        val problems = GlslValidator.validate(ShaderDef("t", "vec4 other(vec2 uv) { return vec4(0); }"))
        assertTrue(problems.any { it.message.contains(ShaderDef.ENTRY_POINT) }, "$problems")
    }

    @Test
    fun `redefining something the generated program owns is caught`() {
        val problems = GlslValidator.validate(ShaderDef("t", "$valid\nvoid main() {}"))
        assertTrue(problems.any { it.message.contains("main") }, "$problems")
    }

    @Test
    fun `an unclosed brace is reported against the line that opened it`() {
        val source = "vec4 ${ShaderDef.ENTRY_POINT}(vec2 uv, float time, vec4 tint) {\n  return vec4(0.0);\n"
        val problems = GlslValidator.validate(ShaderDef("t", source))
        assertTrue(problems.any { it.message.contains("unclosed") }, "$problems")
        assertEquals(1, problems.first { it.message.contains("unclosed") }.line)
    }

    @Test
    fun `braces inside comments are not counted`() {
        val source = """
            |// a stray { in a line comment
            |/* and a { in a block comment */
            |vec4 ${ShaderDef.ENTRY_POINT}(vec2 uv, float time, vec4 tint) { return vec4(0.0); }
        """.trimMargin()
        assertTrue(GlslValidator.validate(ShaderDef("t", source)).isEmpty())
    }

    @Test
    fun `the shipped template validates`() {
        assertEquals(emptyList(), GlslValidator.validate(ShaderDef("t", GlslHelpers.TEMPLATE)))
    }

    @Test
    fun `each shader is renamed and reachable from the dispatch`() {
        val registry = ShaderRegistry(listOf(ShaderDef("a", valid), ShaderDef("b", valid)))
        val composed = GlslComposer.compose(registry)

        assertTrue(composed.contains("vec4 shadr_main_0("), composed.take(400))
        assertTrue(composed.contains("vec4 shadr_main_1("))
        assertTrue(composed.contains("case 0: return shadr_main_0(uv, time, tint);"))
        assertTrue(composed.contains("case 1: return shadr_main_1(uv, time, tint);"))

        assertTrue(
            !composed.contains("vec4 ${ShaderDef.ENTRY_POINT}(vec2"),
            "the un-renamed entry point leaked into the dispatch",
        )
    }

    @Test
    fun `a shader that fails validation becomes a stub and keeps its index`() {
        val registry = ShaderRegistry(
            listOf(ShaderDef("a", valid), ShaderDef("broken", "not glsl at all"), ShaderDef("c", valid)),
        )
        val composed = GlslComposer.compose(registry)

        assertEquals(1, registry["broken"]?.index)
        assertEquals(2, registry["c"]?.index)
        assertTrue(composed.contains("case 1: return shadr_main_1(uv, time, tint);"))
        assertTrue(composed.contains("vec4 shadr_main_1(vec2 uv, float time, vec4 tint) { return vec4(0.0); }"))
    }

    @Test
    fun `an empty registry still produces a compilable dispatch`() {
        val composed = GlslComposer.compose(ShaderRegistry.EMPTY)
        assertTrue(composed.contains("vec4 shadr_dispatch("))
        assertTrue(composed.contains("default: return vec4(0.0);"))
    }

    @Test
    fun `the dispatch declares the encoding the texture is written with`() {
        val composed = GlslComposer.compose(ShaderRegistry.EMPTY)
        assertTrue(composed.contains("#define SHADR_POSITION_ALPHA ${GlslComposer.POSITION_ALPHA}"))
        assertTrue(composed.contains("#define SHADR_SHADER_GRID ${GlslComposer.GRID}"))
    }

    @Test
    fun `macros are scoped to the shader that declared them`() {
        val registry = ShaderRegistry(
            listOf(
                ShaderDef("a", "#define SEED 1.0\n" + valid),
                ShaderDef("b", "#define SEED 42.0\n" + valid),
            ),
        )
        val composed = GlslComposer.compose(registry)
        assertEquals(2, Regex("#undef SEED").findAll(composed).count(), composed)

        assertTrue(registry.conflicts.isEmpty(), "${registry.conflicts}")
    }

    @Test
    fun `two shaders claiming one global name are reported by name`() {
        val body = "float hash(vec2 p) { return p.x; }\n" + valid
        val registry = ShaderRegistry(listOf(ShaderDef("a", body), ShaderDef("b", body)))

        assertEquals(listOf("a", "b"), registry.conflicts["hash"])

        assertEquals(setOf("b"), registry.conflicted)

        val composed = GlslComposer.compose(registry)
        assertTrue(composed.contains("float hash(vec2 p)"), "the winner lost its definition")
        assertEquals(1, Regex("float hash").findAll(composed).count())
        assertTrue(composed.contains("first defined by 'a'"), composed)
    }

    @Test
    fun `the entry point is not treated as a shared name`() {
        val registry = ShaderRegistry(listOf(ShaderDef("a", valid), ShaderDef("b", valid)))
        assertTrue(registry.conflicts.isEmpty(), "${registry.conflicts}")
        assertTrue(registry.conflicted.isEmpty())
    }

    @Test
    fun `the first declaration in a file is seen`() {
        val globals = GlslSymbols.globals("float first(vec2 p) { return p.x; }\n" + valid)
        assertTrue(globals.contains("first"), "$globals")
    }

    @Test
    fun `names used inside a body are not mistaken for declarations`() {
        val source = "float outer(vec2 p) {\n" +
            "    float inner = p.x;\n" +
            "    vec3 local = vec3(inner);\n" +
            "    return local.x;\n" +
            "}\n" + valid
        assertEquals(listOf("outer"), GlslSymbols.globals(source))
    }

    @Test
    fun `a name in a comment is not a declaration`() {
        val globals = GlslSymbols.globals("// float ghost(vec2 p) is not real\n" + valid)
        assertTrue(globals.isEmpty(), "$globals")
    }

    @Test
    fun `a packed tint round-trips both halves`() {
        for (scale in listOf(0.05, 0.5, 1.0, 2.0, 8.0, 32.0, 64.0)) {
            val packed = ShaderTint.encode(Rgb.parse("4cc9f0")!!, scale)
            val back = ShaderTint.decodeScale(packed)

            assertTrue(
                kotlin.math.abs(back / scale - 1.0) < 0.13,
                "scale $scale came back as $back",
            )
        }
    }

    @Test
    fun `packing a scale costs only the low two bits of each channel`() {
        val original = Rgb.parse("4cc9f0")!!
        val packed = ShaderTint.encode(original, 8.0)
        val colour = ShaderTint.decodeColor(packed)

        for (pair in listOf(original.r to colour.r, original.g to colour.g, original.b to colour.b)) {
            assertTrue(pair.second <= pair.first, "a channel got brighter: $pair")
            assertTrue(pair.first - pair.second < 4, "lost more than two bits: $pair")
        }
    }

    @Test
    fun `a scale outside the range clamps to its end`() {
        assertEquals(
            ShaderTint.decodeScale(ShaderTint.encode(Rgb.WHITE, ShaderTint.SCALE_MIN)),
            ShaderTint.decodeScale(ShaderTint.encode(Rgb.WHITE, 0.0001)),
        )
        assertEquals(
            ShaderTint.decodeScale(ShaderTint.encode(Rgb.WHITE, ShaderTint.SCALE_MAX)),
            ShaderTint.decodeScale(ShaderTint.encode(Rgb.WHITE, 10_000.0)),
        )
    }

    @Test
    fun `the GLSL decode agrees with the Kotlin encode`() {
        for (scale in listOf(0.05, 0.37, 1.0, 3.3, 12.0, 64.0)) {
            val packed = ShaderTint.encode(Rgb.parse("ff8800")!!, scale)

            val r = (packed shr 16) and 0xFF
            val g = (packed shr 8) and 0xFF
            val b = packed and 0xFF
            val q = ((r and 3) shl 4) or ((g and 3) shl 2) or (b and 3)
            val glsl = ShaderTint.SCALE_MIN *
                Math.pow(ShaderTint.SCALE_MAX / ShaderTint.SCALE_MIN, q.toDouble() / 63.0)
            assertEquals(ShaderTint.decodeScale(packed), glsl, 1e-9)
        }
    }

    @Test
    fun `the helper library exposes the ray and tint entry points`() {
        for (name in listOf(
            "shadr_ray_origin", "shadr_ray_dir", "shadr_ray_sphere",
            "shadr_tint_rgb", "shadr_tint_scale", "shadr_aspect",
        )) {
            assertTrue(GlslHelpers.SOURCE.contains("$name("), "missing helper: $name")
        }
    }

    @Test
    fun `both programs declare what the ray helpers read`() {
        val names = listOf(
            "shadrWorldPos", "shadrEye", "shadrQuadCentre", "shadrQuadRight", "shadrQuadUp",
        )
        val composed = GlslComposer.compose(ShaderRegistry.EMPTY)
        for (name in names) {
            assertTrue(composed.contains(name), "game program lacks $name")
            assertTrue(
                composed.indexOf(name) < composed.indexOf("shadr_ray_dir"),
                "$name is declared after the helper that reads it",
            )
        }
        val (preamble, epilogue) = GlslComposer.previewParts()
        for (name in names) {
            assertTrue(preamble.contains("vec3 $name;"), "preview lacks $name")
            assertTrue(epilogue.contains(name), "preview never fills $name")
        }
    }

    @Test
    fun `the vertex stage corrects the eye for view bobbing`() {
        val vsh = File("../shaders/overlays/mc_26_2/core/item.vsh").readText()
        assertTrue(
            vsh.contains("shadrWorldPos = Position + ModelOffset;"),
            "the world position must include ModelOffset",
        )

        assertTrue(
            vsh.contains("inverse(mat3(ModelViewMat)) * ModelViewMat[3].xyz"),
            "the eye is not corrected for view bobbing",
        )
    }

    @Test
    fun `the preview offset matches the preamble the editor is given`() {
        val (preamble, epilogue) = GlslComposer.previewParts()
        val (program, offset) = GlslComposer.previewProgram("// author line 1")

        assertEquals("$preamble\n// author line 1\n$epilogue", program)
        assertEquals(preamble.lines().size + 1, offset)

        assertEquals("// author line 1", program.lines()[offset - 1])
    }

    @Test
    fun `diagnostics interpolate, rather than shipping their own template syntax`() {
        val problems = GlslValidator.validate(ShaderDef("t", "#version 330\nnothing here"))
        assertTrue(problems.isNotEmpty())
        for (problem in problems) {
            val rendered = "line ${problem.line}: ${problem.message}"
            assertTrue(
                !rendered.contains("${'$'}{"),
                "a diagnostic shipped un-interpolated template syntax: $rendered",
            )
        }

        val body = "float hash(vec2 p) { return p.x; }\n" + valid
        val composed = GlslComposer.compose(
            ShaderRegistry(listOf(ShaderDef("a", body), ShaderDef("b", body))),
        )
        assertTrue(
            !composed.contains("${'$'}{"),
            "the composed program shipped un-interpolated template syntax",
        )
    }

    private fun period(seconds: Double): Double =
        GlslHelpers.CYCLE_SECONDS / maxOf(1.0, Math.floor(GlslHelpers.CYCLE_SECONDS / maxOf(seconds, 1e-4) + 0.5))

    @Test
    fun `every snapped period divides the clock's cycle exactly`() {
        for (asked in listOf(0.05, 0.5, 1.0, 1.6667, 3.0, 7.0, 60.0, 125.66, 209.44, 600.0, 1199.0, 5000.0)) {
            val snapped = period(asked)
            val repeats = GlslHelpers.CYCLE_SECONDS / snapped
            assertEquals(
                Math.round(repeats).toDouble(), repeats, 1e-9,
                "a period of $asked s snapped to $snapped s, which does not tile the cycle",
            )
            assertTrue(repeats >= 1.0, "asking for $asked s produced a period longer than the cycle")
        }
    }

    @Test
    fun `a period that already divides the cycle is not moved`() {
        for (exact in listOf(1200.0, 600.0, 200.0, 120.0, 3.0, 1200.0 / 720.0)) {
            assertEquals(exact, period(exact), 1e-9)
        }
    }

    @Test
    fun `phase is continuous across the clock's reset`() {
        for (asked in listOf(0.5, 3.0, 7.0, 60.0, 209.44)) {
            val p = period(asked)
            val justBefore = ((GlslHelpers.CYCLE_SECONDS - 1e-6) / p).let { it - Math.floor(it) }
            val justAfter = (0.0 / p).let { it - Math.floor(it) }
            assertTrue(
                justBefore > 0.999 && justAfter < 0.001,
                "a $asked s loop reads $justBefore just before the reset and $justAfter after it",
            )
        }
    }

    @Test
    fun `the cycle macro is defined before the clock that uses it`() {
        val composed = GlslComposer.compose(ShaderRegistry.EMPTY)
        val defined = composed.indexOf("#define SHADR_CYCLE")
        val used = composed.indexOf("float shadr_time()")
        assertTrue(defined >= 0, "SHADR_CYCLE is not defined in the composed program")
        assertTrue(used >= 0, "shadr_time() is not in the composed program")
        assertTrue(defined < used, "shadr_time() uses SHADR_CYCLE before the #define")
    }

    @Test
    fun `the cycle constant matches the macro the shaders compile against`() {
        assertTrue(
            GlslHelpers.SOURCE.contains("#define SHADR_CYCLE ${GlslHelpers.CYCLE_SECONDS}"),
            "GlslHelpers.CYCLE_SECONDS and SHADR_CYCLE have drifted apart",
        )
    }

    @Test
    fun `the preview wraps its clock as the game does`() {
        val (program, _) = GlslComposer.previewProgram(valid)
        assertTrue(
            program.contains("mod(uTime, SHADR_CYCLE)"),
            "the preview runs on an unwrapped clock, so it disagrees with the game",
        )
    }

    @Test
    fun `the preview compiles the same helper text the pack does`() {
        val (preamble, _) = GlslComposer.previewParts()
        assertTrue(
            preamble.contains(GlslHelpers.SOURCE),
            "the preview must use the helper library verbatim, or it is not a preview",
        )
        assertTrue(GlslComposer.compose(ShaderRegistry.EMPTY).contains(GlslHelpers.SOURCE))
    }
}

/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

import dev.shadr.core.shader.EnvironmentEffect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

object WorldChainBuilder {

    private const val VSH = "minecraft:post/shadr_fullscreen"
    private const val BLIT = "minecraft:post/shadr_world_blit"
    private const val MAIN = "minecraft:main"
    private const val TRANSLUCENT = "minecraft:translucent"
    private const val ITEM_ENTITY = "minecraft:item_entity"

    private const val WORK = "shadr:world_work"
    private const val HEADER = "shadr:header"
    private const val OPAQUE = "shadr:opaque"
    private const val AO_A = "shadr:ao_a"
    private const val AO_B = "shadr:ao_b"
    private const val BLOOM_A = "shadr:bloom_a"
    private const val BLOOM_B = "shadr:bloom_b"
    private const val RAY_MASK = "shadr:ray_mask"
    private const val RAY_BLUR = "shadr:ray_blur"
    private const val SSR = "shadr:ssr"

    /** Must match SHADR_HEADER_PIXELS in shadr_header.glsl. */
    const val HEADER_PIXELS = 43

    private const val BLOOM_WIDTH = 480
    private const val BLOOM_HEIGHT = 270
    private const val RAY_WIDTH = 960
    private const val RAY_HEIGHT = 540

    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun compose(base: String, effects: Map<EnvironmentEffect, Map<String, Double>>): String? {
        if (effects.isEmpty()) return null
        val vanilla = Json.parseToJsonElement(base).jsonObject
        val ssao = effects[EnvironmentEffect.SSAO]
        val ssr = effects[EnvironmentEffect.SSR]
        val water = effects[EnvironmentEffect.WATER]
        val fog = effects[EnvironmentEffect.FOG]
        val rays = effects[EnvironmentEffect.GOD_RAYS]
        val bloom = effects[EnvironmentEffect.BLOOM]
        val grade = effects[EnvironmentEffect.GRADING]

        val needHeader = ssao != null || ssr != null || water != null || fog != null || rays != null
        val needOpaque = ssr != null || water != null

        val targets = buildJsonObject {
            vanilla["targets"]?.jsonObject?.forEach { (name, value) -> put(name, value) }
            put(WORK, JsonObject(emptyMap()))
            if (needHeader) put(HEADER, sized(HEADER_PIXELS, 1))
            if (needOpaque) put(OPAQUE, JsonObject(emptyMap()))
            if (ssao != null) {
                put(AO_A, JsonObject(emptyMap()))
                put(AO_B, JsonObject(emptyMap()))
            }
            if (ssr != null) put(SSR, JsonObject(emptyMap()))
            if (rays != null) {
                put(RAY_MASK, sized(RAY_WIDTH, RAY_HEIGHT))
                put(RAY_BLUR, sized(RAY_WIDTH, RAY_HEIGHT))
            }
            if (bloom != null) {
                put(BLOOM_A, sized(BLOOM_WIDTH, BLOOM_HEIGHT))
                put(BLOOM_B, sized(BLOOM_WIDTH, BLOOM_HEIGHT))
            }
        }

        val passes = buildJsonArray {
            if (needHeader) add(headerCopyPass())
            if (ssao != null) {
                add(ssaoPass(ssao))
                add(ssaoBlurPass(ssao))
                add(ssaoCompositePass())
                add(blit(WORK, MAIN))
            }
            if (needOpaque) add(blit(MAIN, OPAQUE))

            vanilla["passes"]?.jsonArray?.forEach { add(it) }

            if (ssr != null) {
                add(ssrPass(ssr, water))
                add(ssrCompositePass(ssr))
                add(blit(WORK, MAIN))
            }
            if (water != null) {
                add(waterPass(water, hasSsr = ssr != null))
                add(blit(WORK, MAIN))
            }
            if (fog != null) {
                add(fogPass(fog))
                add(blit(WORK, MAIN))
            }
            if (rays != null) {
                add(godrayOccludePass(rays))
                add(godrayBlurPass(rays))
                add(godrayCompositePass(rays))
                add(blit(WORK, MAIN))
            }
            if (bloom != null) {
                add(bloomBrightPass(bloom))
                add(bloomBlurPass(bloom, BLOOM_A, BLOOM_B, horizontal = true))
                add(bloomBlurPass(bloom, BLOOM_B, BLOOM_A, horizontal = false))
                add(bloomCompositePass(bloom))
                add(blit(WORK, MAIN))
            }
            if (grade != null) {
                add(gradePass(grade))
                add(blit(WORK, MAIN))
            }
            if (needHeader) {
                add(headerErasePass())
                add(blit(WORK, MAIN))
            }
        }

        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("targets", targets)
                put("passes", passes)
            },
        ) + "\n"
    }

    private fun sized(width: Int, height: Int) = buildJsonObject {
        put("width", width)
        put("height", height)
    }

    private fun pass(
        fragment: String,
        output: String,
        inputs: JsonArray,
        uniforms: JsonObject? = null,
    ) = buildJsonObject {
        put("vertex_shader", VSH)
        put("fragment_shader", fragment)
        put("inputs", inputs)
        put("output", output)
        uniforms?.let { put("uniforms", it) }
    }

    private fun blit(from: String, to: String) =
        pass(BLIT, to, buildJsonArray { add(target("In", from)) })

    private fun target(sampler: String, target: String, bilinear: Boolean = false) = buildJsonObject {
        put("sampler_name", sampler)
        put("target", target)
        if (bilinear) put("bilinear", true)
    }

    private fun depth(sampler: String, target: String) = buildJsonObject {
        put("sampler_name", sampler)
        put("target", target)
        put("use_depth_buffer", true)
    }

    private fun uiDepthInputs() = listOf(
        depth("MainDepth", MAIN),
        depth("TranslucentDepth", TRANSLUCENT),
        depth("ItemEntityDepth", ITEM_ENTITY),
    )

    private fun vec4(vararg values: Number) = buildJsonObject {
        put("type", "vec4")
        put("value", JsonArray(values.map { JsonPrimitive(it.toDouble()) }))
    }

    private fun Map<String, Double>.at(key: String): Double = this[key] ?: 0.0

    private fun Map<String, Double>.channel(key: String, shift: Int): Double {
        val packed = this[key]?.toLong() ?: 0x808080L
        return ((packed shr shift) and 0xFF).toDouble() / 255.0
    }

    private fun colourVec(p: Map<String, Double>, key: String, w: Double) =
        vec4(p.channel(key, 16), p.channel(key, 8), p.channel(key, 0), w)


    private fun headerCopyPass() = pass(
        "minecraft:post/shadr_header_copy", HEADER,
        buildJsonArray { add(target("In", ITEM_ENTITY)) },
    )

    private fun headerErasePass() = pass(
        "minecraft:post/shadr_header_erase", WORK,
        buildJsonArray { add(target("In", MAIN)) },
    )


    private fun ssaoUniforms(p: Map<String, Double>) = buildJsonObject {
        put(
            "ShadrSsaoConfig",
            buildJsonArray {
                add(vec4(p.at("radius"), p.at("intensity"), p.at("bias"), 0.0))
                add(vec4(p.at("samples"), p.at("blur"), p.at("fov"), 0.0))
            },
        )
    }

    private fun ssaoPass(p: Map<String, Double>) = pass(
        "minecraft:post/shadr_ssao", AO_A,
        buildJsonArray {
            add(depth("MainDepth", MAIN))
            add(target("Header", HEADER))
        },
        ssaoUniforms(p),
    )

    private fun ssaoBlurPass(p: Map<String, Double>) = pass(
        "minecraft:post/shadr_ssao_blur", AO_B,
        buildJsonArray {
            add(target("In", AO_A))
            add(depth("MainDepth", MAIN))
        },
        ssaoUniforms(p),
    )

    private fun ssaoCompositePass() = pass(
        "minecraft:post/shadr_ssao_composite", WORK,
        buildJsonArray {
            add(target("In", MAIN))
            add(target("Ao", AO_B))
            add(depth("MainDepth", MAIN))
        },
    )


    private fun ssrRayUniforms(p: Map<String, Double>, water: Map<String, Double>?) = buildJsonObject {
        put(
            "ShadrSsrConfig",
            buildJsonArray {
                add(vec4(p.at("fade"), p.at("maxDistance"), p.at("thickness"), p.at("stride")))
                add(vec4(p.at("maxSteps"), p.at("intensity"), p.at("fov"), 0.0))
                add(
                    vec4(
                        water?.at("waveStrength") ?: 0.35,
                        water?.at("waveScale") ?: 1.0,
                        water?.at("waveSpeed") ?: 1.0,
                        0.0,
                    ),
                )
            },
        )
    }

    private fun ssrPass(p: Map<String, Double>, water: Map<String, Double>?) = pass(
        "minecraft:post/shadr_ssr", SSR,
        buildJsonArray {
            add(target("Opaque", OPAQUE, bilinear = true))
            add(depth("MainDepth", MAIN))
            add(target("Translucent", TRANSLUCENT))
            add(depth("TranslucentDepth", TRANSLUCENT))
            add(target("Header", HEADER))
        },
        ssrRayUniforms(p, water),
    )

    private fun ssrCompositePass(p: Map<String, Double>) = pass(
        "minecraft:post/shadr_ssr_composite", WORK,
        buildJsonArray {
            add(target("In", MAIN))
            add(target("Reflection", SSR, bilinear = true))
        },
        buildJsonObject {
            put(
                "ShadrSsrConfig",
                buildJsonArray {
                    add(vec4(p.at("fade"), p.at("maxDistance"), p.at("thickness"), p.at("stride")))
                    add(vec4(p.at("maxSteps"), p.at("intensity"), p.at("fov"), 0.0))
                },
            )
        },
    )


    private fun waterPass(p: Map<String, Double>, hasSsr: Boolean) = pass(
        "minecraft:post/shadr_water", WORK,
        buildJsonArray {
            add(target("In", MAIN))
            add(target("Opaque", OPAQUE, bilinear = true))
            add(depth("MainDepth", MAIN))
            add(target("Translucent", TRANSLUCENT))
            add(depth("TranslucentDepth", TRANSLUCENT))
            add(depth("ItemEntityDepth", ITEM_ENTITY))
            add(target("Ssr", if (hasSsr) SSR else OPAQUE, bilinear = true))
            add(target("Header", HEADER))
        },
        buildJsonObject {
            put(
                "ShadrWaterConfig",
                buildJsonArray {
                    add(vec4(p.at("waveStrength"), p.at("waveScale"), p.at("waveSpeed"), p.at("refraction")))
                    add(colourVec(p, "absorptionColor", p.at("absorption")))
                    add(colourVec(p, "scatterColor", p.at("scatter")))
                    add(vec4(p.at("sunBoost"), p.at("caustics"), p.at("causticScale"), p.at("foam")))
                    add(vec4(p.at("foamWidth"), if (hasSsr) 1.0 else 0.0, 0.0, 0.0))
                },
            )
        },
    )


    private fun fogPass(p: Map<String, Double>) = pass(
        "minecraft:post/shadr_fog", WORK,
        buildJsonArray {
            add(target("In", MAIN))
            uiDepthInputs().forEach { add(it) }
            add(target("Header", HEADER))
        },
        buildJsonObject {
            put(
                "ShadrFogConfig",
                buildJsonArray {
                    add(vec4(p.at("density"), p.at("falloff"), p.at("height"), p.at("maxDist")))
                    add(colourVec(p, "color", p.at("scatter")))
                },
            )
        },
    )


    private fun godrayUniforms(p: Map<String, Double>) = buildJsonObject {
        put(
            "ShadrGodrayConfig",
            buildJsonArray {
                add(vec4(p.at("intensity"), p.at("decay"), p.at("density"), p.at("weight")))
                add(vec4(p.at("samples"), p.at("focus"), 0.0, 0.0))
            },
        )
    }

    private fun godrayOccludePass(p: Map<String, Double>) = pass(
        "minecraft:post/shadr_godray_occlude", RAY_MASK,
        buildJsonArray {
            add(target("In", MAIN, bilinear = true))
            add(depth("MainDepth", MAIN))
            add(target("Header", HEADER))
        },
        godrayUniforms(p),
    )

    private fun godrayBlurPass(p: Map<String, Double>) = pass(
        "minecraft:post/shadr_godray_blur", RAY_BLUR,
        buildJsonArray {
            add(target("In", RAY_MASK, bilinear = true))
            add(target("Header", HEADER))
        },
        godrayUniforms(p),
    )

    private fun godrayCompositePass(p: Map<String, Double>) = pass(
        "minecraft:post/shadr_godray_composite", WORK,
        buildJsonArray {
            add(target("In", MAIN))
            add(target("Rays", RAY_BLUR, bilinear = true))
            uiDepthInputs().forEach { add(it) }
        },
        godrayUniforms(p),
    )


    private fun bloomUniforms(p: Map<String, Double>) = buildJsonObject {
        put(
            "ShadrBloomConfig",
            buildJsonArray {
                add(vec4(p.at("threshold"), p.at("knee"), p.at("intensity"), p.at("radius")))
            },
        )
    }

    private fun bloomBrightPass(p: Map<String, Double>) = pass(
        "minecraft:post/shadr_bloom_bright", BLOOM_A,
        buildJsonArray {
            add(target("In", MAIN, bilinear = true))
            uiDepthInputs().forEach { add(it) }
        },
        bloomUniforms(p),
    )

    private fun bloomBlurPass(
        p: Map<String, Double>,
        from: String,
        to: String,
        horizontal: Boolean,
    ) = pass(
        "minecraft:post/shadr_blur_box", to,
        buildJsonArray { add(target("In", from, bilinear = true)) },
        buildJsonObject {
            put(
                "ShadrBlurConfig",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "vec2")
                            put(
                                "value",
                                JsonArray(
                                    listOf(
                                        JsonPrimitive(if (horizontal) 1.0 else 0.0),
                                        JsonPrimitive(if (horizontal) 0.0 else 1.0),
                                    ),
                                ),
                            )
                        },
                    )
                    add(
                        buildJsonObject {
                            put("type", "float")
                            put("value", JsonPrimitive(p.at("radius")))
                        },
                    )
                },
            )
        },
    )

    private fun bloomCompositePass(p: Map<String, Double>) = pass(
        "minecraft:post/shadr_bloom_composite", WORK,
        buildJsonArray {
            add(target("In", MAIN))
            add(target("Bloom", BLOOM_A, bilinear = true))
            uiDepthInputs().forEach { add(it) }
        },
        bloomUniforms(p),
    )


    private fun gradePass(p: Map<String, Double>) = pass(
        "minecraft:post/shadr_grade", WORK,
        buildJsonArray {
            add(target("In", MAIN))
            uiDepthInputs().forEach { add(it) }
        },
        buildJsonObject {
            put(
                "ShadrGradeConfig",
                buildJsonArray {
                    add(vec4(p.at("exposure"), p.at("contrast"), p.at("contrastPivot"), p.at("tonemap")))
                    add(vec4(p.at("saturation"), p.at("vibrance"), p.at("temperature"), p.at("tint")))
                    add(colourVec(p, "shadows", 0.0))
                    add(colourVec(p, "midtones", 0.0))
                    add(colourVec(p, "highlights", 0.0))
                    add(vec4(p.at("vignette"), p.at("vignetteRoundness"), p.at("vignetteSmooth"), p.at("aberration")))
                    add(vec4(p.at("grain"), p.at("grainSize"), p.at("sharpen"), p.at("lutStrength")))
                },
            )
        },
    )
}

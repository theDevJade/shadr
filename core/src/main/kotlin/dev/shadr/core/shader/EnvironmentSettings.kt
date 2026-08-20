/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.shader

import java.io.File

object PostChains {
    const val HOST = "creeper"

    const val WORLD_HOST = "transparency"

    const val HOST_PATH = "post_effect/$HOST.json"

    const val WORLD_HOST_PATH = "post_effect/$WORLD_HOST.json"

    const val WORLD_INCLUDE = "include/shadr_world.glsl"

    const val WORLD_MASK = "include/shadr_world_mask.glsl"

    const val WORLD_BLIT = "post/shadr_world_blit.fsh"

    const val FULLSCREEN = "post/shadr_fullscreen.vsh"

    val FRAME_HEADER: List<String> = listOf(
        "include/shadr_sun.glsl",
        "include/shadr_header.glsl",
        "include/shadr_header_vertex.glsl",
        "post/shadr_header_copy.fsh",
        "post/shadr_header_erase.fsh",
    )

    val WORLD_COMMON: List<String> = listOf(
        WORLD_HOST_PATH,
        FULLSCREEN,
        WORLD_INCLUDE,
        WORLD_MASK,
        WORLD_BLIT,
    )
}

private const val G_TONE = "Tone"
private const val G_WAVES = "Waves"
private const val G_COLOUR = "Colour"
private const val G_OPTICS = "Optics"
private const val G_QUALITY = "Quality"

enum class EnvironmentEffect(
    val id: String,
    val title: String,
    val description: String,
    val programs: List<String>,
    val host: String = PostChains.HOST_PATH,
    val params: List<EffectParam> = emptyList(),
) {
    SKY(
        id = "sky",
        title = "Atmospheric sky",
        description = "My version of the sky.",
        programs = listOf("core/sky.vsh", "core/sky.fsh"),
    ),

    CLOUDS(
        id = "clouds",
        title = "Volumetric clouds",
        description = "Raymarched clouds lit by the actual sun.",
        programs = listOf(
            "core/rendertype_clouds.vsh",
            "core/rendertype_clouds.fsh",
            "include/shadr_sun.glsl",
        ),
    ),

    CELESTIALS(
        id = "celestials",
        title = "Procedural sun and moon",
        description = "Sketchy procedural generation instead of ew square.",
        programs = listOf("core/position_tex.fsh"),
    ),

    FROSTED_GLASS(
        id = "blur",
        title = "Frosted glass panels",
        description = "Apple-maxxing",
        programs = listOf(
            PostChains.HOST_PATH,
            PostChains.FULLSCREEN,
            "post/shadr_blur_mask.fsh",
            "post/shadr_blur_dilate.fsh",
            "post/shadr_blur_erode.fsh",
            "post/shadr_blur_extract.fsh",
            "post/shadr_blur_box.fsh",
            "post/shadr_blur_composite.fsh",
            "post/shadr_blur_blit.fsh",
        ),
    ),

    VIDEO(
        id = "video",
        title = "Video panels",
        description = "Plays clips baked into the pack.",
        programs = listOf(
            PostChains.HOST_PATH,
            PostChains.FULLSCREEN,
            "post/shadr_video_state.fsh",
            "post/shadr_video_decode.fsh",
            "post/shadr_video_writeback.fsh",
            "post/shadr_video_composite.fsh",
        ),
    ),

    GRADING(
        id = "grading",
        title = "Colour grading",
        description = "Exposure, contrast, colour balance, tone curve, vignette and grain.",
        programs = PostChains.WORLD_COMMON + listOf("post/shadr_grade.fsh"),
        host = PostChains.WORLD_HOST_PATH,
        params = listOf(
            EffectParam.float("exposure", "Exposure (EV)", 0.0, -4.0, 4.0, 0.05, G_TONE),
            EffectParam.float("contrast", "Contrast", 1.0, 0.0, 2.0, 0.01, G_TONE),
            EffectParam.float("contrastPivot", "Contrast pivot", 0.435, 0.0, 1.0, 0.005, G_TONE),
            EffectParam.enum(
                "tonemap", "Tone curve",
                listOf("none", "reinhard", "aces", "filmic", "hable"), 2, G_TONE,
            ),
            EffectParam.float("saturation", "Saturation", 1.0, 0.0, 2.0, 0.01, G_COLOUR),
            EffectParam.float("vibrance", "Vibrance", 0.0, -1.0, 1.0, 0.01, G_COLOUR),
            EffectParam.float("temperature", "Temperature", 0.0, -100.0, 100.0, 1.0, G_COLOUR),
            EffectParam.float("tint", "Tint", 0.0, -100.0, 100.0, 1.0, G_COLOUR),
            EffectParam.color("shadows", "Shadows", 0x808080, G_COLOUR),
            EffectParam.color("midtones", "Midtones", 0x808080, G_COLOUR),
            EffectParam.color("highlights", "Highlights", 0x808080, G_COLOUR),
            EffectParam.float("vignette", "Vignette", 0.0, 0.0, 1.0, 0.01, G_OPTICS),
            EffectParam.float("vignetteRoundness", "Vignette roundness", 0.6, 0.0, 1.0, 0.01, G_OPTICS),
            EffectParam.float("vignetteSmooth", "Vignette softness", 0.5, 0.01, 1.0, 0.01, G_OPTICS),
            EffectParam.float("aberration", "Chromatic aberration", 0.0, 0.0, 1.0, 0.01, G_OPTICS),
            EffectParam.float("grain", "Film grain", 0.0, 0.0, 1.0, 0.01, G_OPTICS),
            EffectParam.float("grainSize", "Grain size", 1.0, 0.5, 4.0, 0.1, G_OPTICS),
            EffectParam.float("sharpen", "Sharpen", 0.0, 0.0, 1.0, 0.01, G_OPTICS),
            EffectParam.float("lutStrength", "LUT strength", 0.0, 0.0, 1.0, 0.01, G_OPTICS),
        ),
    ),

    BLOOM(
        id = "bloom",
        title = "Bloom",
        description = "BLOOM!!",
        programs = PostChains.WORLD_COMMON + listOf(
            "post/shadr_bloom_bright.fsh",
            "post/shadr_bloom_composite.fsh",
            "post/shadr_blur_box.fsh",
        ),
        host = PostChains.WORLD_HOST_PATH,
        params = listOf(
            EffectParam.float("threshold", "Threshold", 0.75, 0.0, 4.0, 0.05, G_TONE),
            EffectParam.float("knee", "Soft knee", 0.5, 0.0, 1.0, 0.01, G_TONE),
            EffectParam.float("intensity", "Intensity", 0.6, 0.0, 2.0, 0.01, G_TONE),
            EffectParam.float("radius", "Radius", 4.0, 1.0, 8.0, 0.5, G_QUALITY),
        ),
    ),

    GOD_RAYS(
        id = "godrays",
        title = "God rays",
        description = "Shaders !.",
        programs = PostChains.WORLD_COMMON + PostChains.FRAME_HEADER + listOf(
            "post/shadr_godray_occlude.fsh",
            "post/shadr_godray_blur.fsh",
            "post/shadr_godray_composite.fsh",
        ),
        host = PostChains.WORLD_HOST_PATH,
        params = listOf(
            EffectParam.float("intensity", "Intensity", 0.5, 0.0, 2.0, 0.01, G_TONE),
            EffectParam.float("decay", "Decay", 0.97, 0.8, 1.0, 0.005, G_TONE),
            EffectParam.float("density", "Density", 0.6, 0.0, 1.0, 0.01, G_TONE),
            EffectParam.float("weight", "Weight", 0.35, 0.0, 1.0, 0.01, G_TONE),
            EffectParam.float("samples", "Samples", 24.0, 8.0, 64.0, 1.0, G_QUALITY),
            EffectParam.float("focus", "Sun tightness", 96.0, 1.0, 512.0, 1.0, G_QUALITY),
        ),
    ),

    SSAO(
        id = "ssao",
        title = "Ambient occlusion",
        description = "Needs Fabulous graphics.",
        programs = PostChains.WORLD_COMMON + PostChains.FRAME_HEADER + listOf(
            "post/shadr_ssao.fsh",
            "post/shadr_ssao_blur.fsh",
            "post/shadr_ssao_composite.fsh",
        ),
        host = PostChains.WORLD_HOST_PATH,
        params = listOf(
            EffectParam.float("radius", "Radius (blocks)", 1.0, 0.1, 4.0, 0.05, G_TONE),
            EffectParam.float("intensity", "Intensity", 0.8, 0.0, 2.0, 0.01, G_TONE),
            EffectParam.float("bias", "Bias", 0.05, 0.0, 0.5, 0.005, G_TONE),
            EffectParam.float("samples", "Samples", 12.0, 4.0, 32.0, 1.0, G_QUALITY),
            EffectParam.float("blur", "Denoise radius", 2.0, 0.0, 4.0, 0.5, G_QUALITY),
            EffectParam.float("fov", "Fallback FOV", 70.0, 30.0, 120.0, 1.0, G_QUALITY),
        ),
    ),

    SSR(
        id = "ssr",
        title = "Screen space reflections",
        description = "Reflects the world wierdly. Needs Fabulous graphics.",
        programs = PostChains.WORLD_COMMON + PostChains.FRAME_HEADER + listOf(
            "include/shadr_watermath.glsl",
            "post/shadr_ssr.fsh",
            "post/shadr_ssr_composite.fsh",
        ),
        host = PostChains.WORLD_HOST_PATH,
        params = listOf(
            EffectParam.float("intensity", "Intensity", 0.7, 0.0, 1.0, 0.01, G_TONE),
            EffectParam.float("maxSteps", "Ray steps", 32.0, 8.0, 64.0, 1.0, G_QUALITY),
            EffectParam.float("maxDistance", "Ray length (blocks)", 24.0, 1.0, 64.0, 1.0, G_QUALITY),
            EffectParam.float("thickness", "Hit thickness", 0.4, 0.05, 2.0, 0.05, G_QUALITY),
            EffectParam.float("stride", "Stride", 2.0, 1.0, 8.0, 0.5, G_QUALITY),
            EffectParam.float("fade", "Edge fade", 0.3, 0.0, 1.0, 0.01, G_OPTICS),
            EffectParam.float("fov", "Fallback FOV", 70.0, 30.0, 120.0, 1.0, G_QUALITY),
        ),
    ),

    WATER(
        id = "water",
        title = "Water",
        description = "Water stuff. Needs Fabulous graphics.",
        programs = PostChains.WORLD_COMMON + PostChains.FRAME_HEADER + listOf(
            "include/shadr_watermath.glsl",
            "post/shadr_water.fsh",
        ),
        host = PostChains.WORLD_HOST_PATH,
        params = listOf(
            EffectParam.float("waveStrength", "Wave strength", 0.35, 0.0, 1.0, 0.01, G_WAVES),
            EffectParam.float("waveScale", "Wave scale", 1.0, 0.1, 6.0, 0.05, G_WAVES),
            EffectParam.float("waveSpeed", "Wave speed", 1.0, 0.0, 4.0, 0.05, G_WAVES),
            EffectParam.float("refraction", "Refraction", 0.35, 0.0, 1.0, 0.01, G_WAVES),
            EffectParam.float("absorption", "Absorption", 1.0, 0.0, 3.0, 0.02, G_TONE),
            EffectParam.color("absorptionColor", "Water tint", 0x3F7FA6, G_TONE),
            EffectParam.float("scatter", "Scattering", 0.3, 0.0, 1.0, 0.01, G_TONE),
            EffectParam.color("scatterColor", "Scatter colour", 0x07293D, G_TONE),
            EffectParam.float("sunBoost", "Sun scattering", 0.75, 0.0, 2.0, 0.01, G_TONE),
            EffectParam.float("caustics", "Caustics", 0.8, 0.0, 2.0, 0.01, G_OPTICS),
            EffectParam.float("causticScale", "Caustic scale", 2.0, 0.25, 8.0, 0.05, G_OPTICS),
            EffectParam.float("foam", "Shore foam", 0.6, 0.0, 1.0, 0.01, G_OPTICS),
            EffectParam.float("foamWidth", "Foam width (blocks)", 0.45, 0.0, 2.0, 0.01, G_OPTICS),
        ),
    ),

    FOG(
        id = "fog",
        title = "Volumetric fog",
        description = "FOGG. Needs Fabulous graphics.",
        programs = PostChains.WORLD_COMMON + PostChains.FRAME_HEADER + listOf(
            "post/shadr_fog.fsh",
        ),
        host = PostChains.WORLD_HOST_PATH,
        params = listOf(
            EffectParam.float("density", "Density", 0.02, 0.0, 0.3, 0.002, G_TONE),
            EffectParam.float("falloff", "Height falloff", 0.12, 0.005, 1.0, 0.005, G_TONE),
            EffectParam.float("height", "Base height", 63.0, 0.0, 320.0, 1.0, G_TONE),
            EffectParam.float("maxDist", "Reach (blocks)", 192.0, 32.0, 512.0, 8.0, G_QUALITY),
            EffectParam.color("color", "Fog colour", 0xB8C8DD, G_OPTICS),
            EffectParam.float("scatter", "Sun scattering", 0.6, 0.0, 2.0, 0.01, G_OPTICS),
        ),
    ),
    ;

    val isWorldEffect: Boolean get() = host == PostChains.WORLD_HOST_PATH

    fun defaults(): Map<String, Double> = params.associate { it.key to it.default }

    companion object {
        fun parse(raw: String?): EnvironmentEffect? =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) }

        val worldEffects: List<EnvironmentEffect> get() = entries.filter { it.isWorldEffect }
    }
}

class EnvironmentSettings(private val file: File) {

    private val enabled = java.util.concurrent.ConcurrentHashMap<EnvironmentEffect, Boolean>()

    private val values =
        java.util.concurrent.ConcurrentHashMap<EnvironmentEffect, MutableMap<String, Double>>()

    init {
        load()
    }

    operator fun get(effect: EnvironmentEffect): Boolean = enabled[effect] ?: false

    fun isEnabled(effect: EnvironmentEffect): Boolean = get(effect)

    fun set(effect: EnvironmentEffect, value: Boolean) {
        enabled[effect] = value
        save()
    }

    fun paramsOf(effect: EnvironmentEffect): Map<String, Double> {
        val stored = values[effect].orEmpty()
        return effect.params.associate { param ->
            param.key to param.clamp(stored[param.key] ?: param.default)
        }
    }

    fun setParam(effect: EnvironmentEffect, key: String, value: Double): Boolean {
        val param = effect.params.firstOrNull { it.key == key } ?: return false
        values.getOrPut(effect) { java.util.concurrent.ConcurrentHashMap() }[key] = param.clamp(value)
        save()
        return true
    }

    fun applyPreset(effect: EnvironmentEffect, preset: Map<String, Double>): Int {
        var applied = 0
        val target = values.getOrPut(effect) { java.util.concurrent.ConcurrentHashMap() }
        for (param in effect.params) {
            val raw = preset[param.key] ?: continue
            target[param.key] = param.clamp(raw)
            applied++
        }
        if (applied > 0) save()
        return applied
    }

    fun all(): Map<EnvironmentEffect, Boolean> =
        EnvironmentEffect.entries.associateWith { get(it) }

    fun allParams(): Map<EnvironmentEffect, Map<String, Double>> =
        EnvironmentEffect.entries.associateWith { paramsOf(it) }

    /** Enabled world effects with their resolved values, in declaration order. */
    fun activeWorldEffects(): Map<EnvironmentEffect, Map<String, Double>> =
        EnvironmentEffect.worldEffects.filter { get(it) }.associateWith { paramsOf(it) }

    private fun load() {
        enabled.clear()
        values.clear()
        if (!file.isFile) return
        for (line in file.readLines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val key = trimmed.substringBefore(SEPARATOR).trim()
            val raw = trimmed.substringAfter(SEPARATOR, "").trim()
            val effect = EnvironmentEffect.parse(key.substringBefore(DOT))
                ?: EnvironmentEffect.parse(key) ?: continue
            if (!key.contains(DOT)) {
                enabled[effect] = raw.toBooleanStrictOrNull() ?: false
                continue
            }
            val paramKey = key.substringAfter(DOT)
            val param = effect.params.firstOrNull { it.key == paramKey } ?: continue
            raw.toDoubleOrNull()?.let {
                values.getOrPut(effect) { java.util.concurrent.ConcurrentHashMap() }[paramKey] =
                    param.clamp(it)
            }
        }
    }

    private fun save() {
        file.parentFile?.mkdirs()
        file.writeText(
            buildString {
                appendLine("# Which vanilla programs shadr overrides. Written by the editor.")
                for (effect in EnvironmentEffect.entries) {
                    appendLine("${effect.id}=${get(effect)}")
                    val stored = values[effect].orEmpty()
                    for (param in effect.params) {
                        val value = stored[param.key] ?: continue
                        appendLine("${effect.id}$DOT${param.key}=${format(value)}")
                    }
                }
            },
        )
    }

    private fun format(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private companion object {
        const val SEPARATOR = '='
        const val DOT = '.'
    }
}

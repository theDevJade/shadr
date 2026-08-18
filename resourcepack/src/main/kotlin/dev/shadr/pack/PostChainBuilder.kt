/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

object PostChainBuilder {

    const val TARGET_PREV = "shadr:video_prev"
    const val TARGET_CUR = "shadr:video_cur"
    const val TARGET_OUT = "shadr:video_out"

    const val TARGET_STATE_PREV = "shadr:video_state_prev"

    const val TARGET_STATE = "shadr:video_state"

    const val STATE_WIDTH = 2

    const val DECODE = "minecraft:post/shadr_video_decode"
    const val WRITEBACK = "minecraft:post/shadr_video_writeback"
    const val COMPOSITE = "minecraft:post/shadr_video_composite"
    const val STATE = "minecraft:post/shadr_video_state"
    const val FULLSCREEN = "minecraft:post/shadr_fullscreen"

    const val TARGET_STREAM_OUT = "shadr:stream_out"
    const val PROBE = "minecraft:post/shadr_stream_probe"

    const val TARGET_SCODEC_PREV = "shadr:scodec_prev"
    const val TARGET_SCODEC_CUR = "shadr:scodec_cur"
    const val TARGET_SCODEC_STATE_PREV = "shadr:scodec_state_prev"
    const val TARGET_SCODEC_STATE = "shadr:scodec_state"
    const val TARGET_SCODEC_OUT = "shadr:scodec_out"
    const val SCODEC_STATE = "minecraft:post/shadr_scodec_state"
    const val SCODEC_DECODE = "minecraft:post/shadr_scodec_decode"

    val PROGRAMS = listOf(
        "post/shadr_fullscreen.vsh",
        "post/shadr_video_state.fsh",
        "post/shadr_video_decode.fsh",
        "post/shadr_video_writeback.fsh",
        "post/shadr_video_composite.fsh",
    )

    val STREAM_PROGRAMS = listOf(
        "post/shadr_fullscreen.vsh",
        "post/shadr_video_writeback.fsh",
        "post/shadr_stream_probe.fsh",
    )

    val CODEC_PROGRAMS = listOf(
        "post/shadr_fullscreen.vsh",
        "post/shadr_video_writeback.fsh",
        "post/shadr_video_composite.fsh",
        "post/shadr_scodec_state.fsh",
        "post/shadr_scodec_decode.fsh",
    )

    data class Video(
        val width: Int,
        val height: Int,
        val frameCount: Int,
        val fps: Double,
        val startSeconds: Double,
        val data: String,
        val dataWidth: Int,
        val dataHeight: Int,
        val superColumns: Int,
        val superblocksPerFrame: Int,
        val codebookBase: Int = 0,
    )

    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun compose(blurChain: String?, video: Video?, stream: dev.shadr.core.stream.StreamGeometry? = null): String? {
        val codec = stream?.takeIf { it.codec }
        val probe = stream?.takeIf { it.probe && !it.codec }
        if (video == null && probe == null && codec == null) return blurChain
        val base = blurChain?.let { Json.parseToJsonElement(it).jsonObject }

        val targets = buildJsonObject {
            base?.get("targets")?.jsonObject?.forEach { (name, value) -> put(name, value) }
            if (video != null) {
                put(TARGET_PREV, target(video.width, video.height, persistent = true))
                put(TARGET_CUR, target(video.width, video.height, persistent = false))
                put(TARGET_STATE_PREV, target(STATE_WIDTH, 1, persistent = true))
                put(TARGET_STATE, target(STATE_WIDTH, 1, persistent = false))
                put(TARGET_OUT, JsonObject(emptyMap()))
            }
            if (probe != null) put(TARGET_STREAM_OUT, JsonObject(emptyMap()))
            if (codec != null) {
                val frame = dev.shadr.core.stream.StreamPresets.CODEC_1080
                put(TARGET_SCODEC_PREV, target(frame.frameWidth, frame.frameHeight, persistent = true))
                put(TARGET_SCODEC_CUR, target(frame.frameWidth, frame.frameHeight, persistent = false))
                put(TARGET_SCODEC_STATE_PREV, target(STATE_WIDTH, 1, persistent = true))
                put(TARGET_SCODEC_STATE, target(STATE_WIDTH, 1, persistent = false))
                put(TARGET_SCODEC_OUT, JsonObject(emptyMap()))
            }
        }

        val passes = buildJsonArray {
            if (video != null) {
                add(statePass(video))
                add(decodePass(video))
                add(blit(TARGET_CUR, TARGET_PREV))
                add(blit(TARGET_STATE, TARGET_STATE_PREV))
            }
            base?.get("passes")?.jsonArray?.forEach { add(it) }
            if (video != null) {
                add(compositePass())
                add(blit(TARGET_OUT, "minecraft:main"))
            }
            if (probe != null) {
                add(probePass(probe))
                add(blit(TARGET_STREAM_OUT, "minecraft:main"))
            }
            if (codec != null) {
                add(scodecStatePass(codec))
                add(scodecDecodePass(codec))
                add(blit(TARGET_SCODEC_CUR, TARGET_SCODEC_PREV))
                add(blit(TARGET_SCODEC_STATE, TARGET_SCODEC_STATE_PREV))
                add(scodecCompositePass())
                add(blit(TARGET_SCODEC_OUT, "minecraft:main"))
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

    private fun target(width: Int, height: Int, persistent: Boolean) = buildJsonObject {
        put("width", width)
        put("height", height)
        if (persistent) put("persistent", true)
    }

    private fun statePass(video: Video) = buildJsonObject {
        put("vertex_shader", FULLSCREEN)
        put("fragment_shader", STATE)
        put("inputs", buildJsonArray { add(targetInput("State", TARGET_STATE_PREV)) })
        put("output", TARGET_STATE)
        put("uniforms", uniforms(video))
    }

    private fun decodePass(video: Video) = buildJsonObject {
        put("vertex_shader", FULLSCREEN)
        put("fragment_shader", DECODE)
        put(
            "inputs",
            buildJsonArray {
                add(targetInput("Prev", TARGET_PREV))
                add(targetInput("State", TARGET_STATE))
                add(targetInput("LastState", TARGET_STATE_PREV))
                add(
                    buildJsonObject {
                        put("sampler_name", "Data")
                        put("location", video.data)
                        put("width", video.dataWidth)
                        put("height", video.dataHeight)
                        put("bilinear", false)
                    },
                )
            },
        )
        put("output", TARGET_CUR)
        put("uniforms", uniforms(video))
    }

    private fun uniforms(video: Video) = buildJsonObject {
        put(
            "ShadrVideoConfig",
            buildJsonArray {
                add(vec4(video.width, video.height, video.superColumns, video.superblocksPerFrame))
                add(vec4(video.frameCount, video.fps, video.startSeconds, video.codebookBase))
            },
        )
    }

    private fun compositePass() = buildJsonObject {
        put("vertex_shader", FULLSCREEN)
        put("fragment_shader", COMPOSITE)
        put(
            "inputs",
            buildJsonArray {
                add(targetInput("In", "minecraft:main"))
                add(targetInput("Frame", TARGET_CUR))
            },
        )
        put("output", TARGET_OUT)
    }

    private fun probePass(stream: dev.shadr.core.stream.StreamGeometry) = buildJsonObject {
        put("vertex_shader", FULLSCREEN)
        put("fragment_shader", PROBE)
        put("inputs", buildJsonArray { add(targetInput("In", "minecraft:main")) })
        put("output", TARGET_STREAM_OUT)
        put(
            "uniforms",
            buildJsonObject {
                put(
                    "ShadrStreamConfig",
                    buildJsonArray {
                        add(vec4(stream.regionX, stream.regionY, stream.columns, stream.rows))
                        add(vec4(stream.slots, stream.probeMode, 0, 0))
                    },
                )
            },
        )
    }

    private fun streamUniforms(stream: dev.shadr.core.stream.StreamGeometry) = buildJsonObject {
        put(
            "ShadrStreamConfig",
            buildJsonArray {
                add(vec4(stream.regionX, stream.regionY, stream.columns, stream.rows))
                add(vec4(stream.slots, stream.probeMode, 0, 0))
            },
        )
    }

    private fun scodecStatePass(stream: dev.shadr.core.stream.StreamGeometry) = buildJsonObject {
        put("vertex_shader", FULLSCREEN)
        put("fragment_shader", SCODEC_STATE)
        put(
            "inputs",
            buildJsonArray {
                add(targetInput("In", "minecraft:main"))
                add(targetInput("LastState", TARGET_SCODEC_STATE_PREV))
            },
        )
        put("output", TARGET_SCODEC_STATE)
        put("uniforms", streamUniforms(stream))
    }

    private fun scodecDecodePass(stream: dev.shadr.core.stream.StreamGeometry) = buildJsonObject {
        put("vertex_shader", FULLSCREEN)
        put("fragment_shader", SCODEC_DECODE)
        put(
            "inputs",
            buildJsonArray {
                add(targetInput("In", "minecraft:main"))
                add(targetInput("Prev", TARGET_SCODEC_PREV))
                add(targetInput("State", TARGET_SCODEC_STATE))
            },
        )
        put("output", TARGET_SCODEC_CUR)
        put("uniforms", streamUniforms(stream))
    }

    private fun scodecCompositePass() = buildJsonObject {
        put("vertex_shader", FULLSCREEN)
        put("fragment_shader", COMPOSITE)
        put(
            "inputs",
            buildJsonArray {
                add(targetInput("In", "minecraft:main"))
                add(
                    buildJsonObject {
                        put("sampler_name", "Frame")
                        put("target", TARGET_SCODEC_CUR)
                        put("bilinear", true)
                    },
                )
            },
        )
        put("output", TARGET_SCODEC_OUT)
    }

    private fun blit(from: String, to: String) = buildJsonObject {
        put("vertex_shader", FULLSCREEN)
        put("fragment_shader", WRITEBACK)
        put("inputs", buildJsonArray { add(targetInput("In", from)) })
        put("output", to)
    }

    private fun targetInput(sampler: String, target: String) = buildJsonObject {
        put("sampler_name", sampler)
        put("target", target)
    }

    private fun vec4(vararg values: Number) = buildJsonObject {
        put("type", "vec4")
        put("value", JsonArray(values.map { JsonPrimitive(it.toDouble()) }))
    }
}

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

    val PROGRAMS = listOf(
        "post/shadr_fullscreen.vsh",
        "post/shadr_video_state.fsh",
        "post/shadr_video_decode.fsh",
        "post/shadr_video_writeback.fsh",
        "post/shadr_video_composite.fsh",
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
    )

    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun compose(blurChain: String?, video: Video?): String? {
        if (video == null) return blurChain
        val base = blurChain?.let { Json.parseToJsonElement(it).jsonObject }

        val targets = buildJsonObject {
            base?.get("targets")?.jsonObject?.forEach { (name, value) -> put(name, value) }
            put(TARGET_PREV, target(video.width, video.height, persistent = true))
            put(TARGET_CUR, target(video.width, video.height, persistent = false))
            put(TARGET_STATE_PREV, target(STATE_WIDTH, 1, persistent = true))
            put(TARGET_STATE, target(STATE_WIDTH, 1, persistent = false))
            put(TARGET_OUT, JsonObject(emptyMap()))
        }

        val passes = buildJsonArray {
            add(statePass(video))
            add(decodePass(video))
            add(blit(TARGET_CUR, TARGET_PREV))
            add(blit(TARGET_STATE, TARGET_STATE_PREV))
            base?.get("passes")?.jsonArray?.forEach { add(it) }
            add(compositePass())
            add(blit(TARGET_OUT, "minecraft:main"))
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
                add(vec4(video.frameCount, video.fps, video.startSeconds, 0.0))
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

/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
#define SHADR_VIDEO_MARKER_ALPHA 250
#define SHADR_VIDEO_KEY 2
#define SHADR_VIDEO_UV_MAX 2047
#define SHADR_VIDEO_UV_CONTINUITY 64

#define SHADR_VIDEO_CYCLE 1200.0

float shadr_video_time(float gameTime) {
    return gameTime * SHADR_VIDEO_CYCLE;
}

float shadr_video_period(float seconds) {
    return SHADR_VIDEO_CYCLE / max(1.0, floor(SHADR_VIDEO_CYCLE / max(seconds, 1e-4) + 0.5));
}

int shadr_video_target_frame(float gameTime, float frameCount, float fps, float start) {
    float period = shadr_video_period(frameCount / fps);
    float phase = fract((shadr_video_time(gameTime) - start) / period);
    return int(min(floor(phase * frameCount), frameCount - 1.0));
}

vec3 shadr_video_pack(vec2 uv) {
    ivec2 q = ivec2(round(clamp(uv, 0.0, 1.0) * float(SHADR_VIDEO_UV_MAX)));
    return vec3(
        float((SHADR_VIDEO_KEY << 6) | (q.x >> 5)),
        float(((q.x & 0x1F) << 3) | (q.y >> 8)),
        float(q.y & 0xFF)
    ) / 255.0;
}

ivec2 shadr_video_unpack(vec3 colour) {
    ivec3 c = ivec3(round(colour * 255.0));
    if ((c.r >> 6) != SHADR_VIDEO_KEY) {
        return ivec2(-1);
    }
    return ivec2(((c.r & 0x3F) << 5) | (c.g >> 3), ((c.g & 0x07) << 8) | c.b);
}

bool shadr_video_adjoins(ivec2 centre, vec3 colour) {
    ivec2 n = shadr_video_unpack(colour);
    if (n.x < 0) {
        return false;
    }
    ivec2 d = abs(n - centre);
    return d.x <= SHADR_VIDEO_UV_CONTINUITY && d.y <= SHADR_VIDEO_UV_CONTINUITY;
}

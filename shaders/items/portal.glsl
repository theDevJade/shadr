/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
// @description A raymarched portal you can look into
// @preview 9d4cf0

#define PT_STEPS 48
#define PT_THROAT 0.34
#define PT_DEPTH 2.6

float pt_tunnel(vec3 p, float time) {
    float twist = p.z * 0.6 + shadr_phase(time, 24.0) * SHADR_TAU;
    vec2 swirled = shadr_rotate(p.xy, twist);
    float ripple = shadr_fbm(vec2(atan(swirled.y, swirled.x) * 1.6, p.z * 1.3)) - 0.5;
    return length(p.xy) - (PT_THROAT + ripple * 0.16);
}

vec4 shadr_main(vec2 uv, float time, vec4 tint) {
    float aspect = shadr_aspect(uv);
    vec2 p = shadr_centered(uv, vec2(aspect, 1.0));

    float mouth = shadr_aa((length(p) - 0.86) * 2.2);
    if (mouth < 0.001) return vec4(0.0);

    vec3 ro = vec3(p, -1.0);
    vec3 rd = normalize(vec3(p * 0.55, 1.0));

    vec3 glow = vec3(0.0);
    float travelled = 0.0;
    for (int i = 0; i < PT_STEPS; i++) {
        vec3 at = ro + rd * travelled;
        float d = pt_tunnel(at, time);

        float shell = exp(-abs(d) * 9.0);
        float depth = 1.0 - clamp(travelled / PT_DEPTH, 0.0, 1.0);
        vec3 band = shadr_hsv(vec3(fract(0.70 + at.z * 0.05), 0.75, 1.0));
        glow += mix(band, tint.rgb, 0.45) * shell * depth * 0.09;

        travelled += max(abs(d) * 0.7, 0.02);
        if (travelled > PT_DEPTH) break;
    }

    float rim = exp(-abs(length(p) - 0.86) * 22.0);
    glow += tint.rgb * rim * 1.6;

    float alpha = clamp(shadr_luma(glow) * 1.4 + rim, 0.0, 1.0) * mouth * tint.a;
    return shadr_resolve(glow, alpha);
}

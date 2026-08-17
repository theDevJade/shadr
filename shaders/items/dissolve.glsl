/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
// @description A noise dissolve with a burning edge, looping in and out
// @preview f06c4c

float ds_mask(vec2 p) {
    return 0.65 * shadr_noise(p * 5.0) + 0.35 * shadr_noise(p * 13.0);
}

vec4 shadr_main(vec2 uv, float time, vec4 tint) {
    float aspect = shadr_aspect(uv);
    vec2 p = vec2(uv.x * aspect, uv.y);

    float cycle = shadr_phase(time, 8.0);
    float threshold = abs(cycle * 2.0 - 1.0);

    float mask = ds_mask(p);
    float front = mask - threshold;

    float body = shadr_aa(-front * 4.0);
    float burn = exp(-abs(front) * 26.0) * step(0.02, threshold);

    vec3 ember = mix(vec3(1.0, 0.35, 0.05), vec3(1.0, 0.85, 0.4), clamp(burn * 1.4, 0.0, 1.0));
    vec3 colour = mix(tint.rgb, ember, clamp(burn * 1.6, 0.0, 1.0));

    return shadr_resolve(colour, tint.a * clamp(body + burn * 0.9, 0.0, 1.0));
}

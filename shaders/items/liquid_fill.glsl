/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
// @description A container filling with a waved liquid surface, for a cooldown or a tank
// @preview 4cc9f0

vec4 shadr_main(vec2 uv, float time, vec4 tint) {
    float level = clamp(shadr_tint_scale(tint), 0.0, 1.0);
    vec3 liquid = shadr_tint_rgb(tint);

    float surfaceY = 1.0 - level;

    float wave =
          0.014 * sin(uv.x * 18.0 + shadr_phase(time, 2.4) * SHADR_TAU)
        + 0.008 * sin(uv.x * 31.0 - shadr_phase(time, 3.75) * SHADR_TAU);

    float depth = (surfaceY + wave) - uv.y;
    float body = shadr_aa(-depth * 30.0);

    float meniscus = exp(-abs(depth) * 90.0);
    float shade = 0.75 + 0.35 * clamp(depth * 1.6, 0.0, 1.0);

    vec3 colour = liquid * shade + vec3(meniscus * 0.55);
    return shadr_resolve(colour, tint.a * clamp(body + meniscus * 0.7, 0.0, 1.0));
}

/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
// @description A ring that pulses outward, good for cooldowns and pings
// @preview f0a04c

vec4 shadr_main(vec2 uv, float time, vec4 tint) {
    vec2 p = shadr_centered(uv, vec2(1.0));

    float phase = shadr_phase(time, 1.0 / 0.6);
    float ring = abs(shadr_sd_circle(p, phase * 0.9)) - 0.04;

    float alpha = shadr_aa(ring) * (1.0 - phase);
    return shadr_resolve(tint.rgb, alpha * tint.a);
}

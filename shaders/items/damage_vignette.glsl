/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
// @description A red edge vignette with a heartbeat, for low-health feedback
// @preview c03030

vec4 shadr_main(vec2 uv, float time, vec4 tint) {
    vec2 p = uv * 2.0 - 1.0;

    float edge = clamp((length(p) - 0.55) / 0.75, 0.0, 1.0);
    edge *= edge;

    float phase = shadr_phase(time, 1.2);
    float beat = exp(-phase * 14.0) + 0.6 * exp(-abs(phase - 0.18) * 22.0);

    float alpha = edge * (0.55 + 0.45 * beat);
    return shadr_resolve(tint.rgb, tint.a * clamp(alpha, 0.0, 1.0));
}

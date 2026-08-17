/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
#define SHADR_HUD_DEPTH_BASE 0.95
#define SHADR_LAYER_TO_DEPTH 0.000001

#define SHADR_BLUR_PANEL_LAYER -5000.0
#define SHADR_BLUR_PANEL_DEPTH (SHADR_HUD_DEPTH_BASE + SHADR_BLUR_PANEL_LAYER * SHADR_LAYER_TO_DEPTH)

#define SHADR_BLUR_PANEL_EPSILON 0.0005

bool shadr_is_blur_panel(float depth) {
    return abs(depth - SHADR_BLUR_PANEL_DEPTH) < SHADR_BLUR_PANEL_EPSILON;
}

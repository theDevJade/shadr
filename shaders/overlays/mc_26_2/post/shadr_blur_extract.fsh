#version 330

#moj_import <shadr_post.glsl>

uniform sampler2D InSampler;
uniform sampler2D InDepthSampler;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    float depth = texture(InDepthSampler, texCoord).r;

    bool isUi = depth >= SHADR_HUD_DEPTH_BASE - SHADR_BLUR_PANEL_EPSILON
        || shadr_is_blur_panel(depth);

    fragColor = isUi ? vec4(0.0) : vec4(texture(InSampler, texCoord).rgb, 1.0);
}

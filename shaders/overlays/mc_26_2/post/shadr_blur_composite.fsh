#version 330

#moj_import <shadr_post.glsl>

uniform sampler2D InSampler;
uniform sampler2D InDepthSampler;
uniform sampler2D BlurSampler;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 main = texture(InSampler, texCoord);
    float depth = texture(InDepthSampler, texCoord).r;

    if (!shadr_is_blur_panel(depth)) {
        fragColor = main;
        return;
    }

    vec4 blurred = texture(BlurSampler, texCoord);

    vec3 backdrop = blurred.a > 0.001 ? blurred.rgb : main.rgb;

    fragColor = vec4(mix(backdrop, main.rgb, 0.35), 1.0);
}

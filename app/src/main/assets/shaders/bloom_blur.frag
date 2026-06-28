#version 300 es
precision highp float;

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uTex;
uniform vec2 uDirection;  // (1/width, 0) or (0, 1/height), scaled by step

// 9-tap Gaussian, separable. Run horizontally then vertically.
// Weights from a σ ≈ 2 Gaussian, normalized to sum 1.
void main() {
    vec3 c = vec3(0.0);
    c += texture(uTex, vUv - uDirection * 4.0).rgb * 0.0162162162;
    c += texture(uTex, vUv - uDirection * 3.0).rgb * 0.0540540541;
    c += texture(uTex, vUv - uDirection * 2.0).rgb * 0.1216216216;
    c += texture(uTex, vUv - uDirection * 1.0).rgb * 0.1945945946;
    c += texture(uTex, vUv                   ).rgb * 0.2270270270;
    c += texture(uTex, vUv + uDirection * 1.0).rgb * 0.1945945946;
    c += texture(uTex, vUv + uDirection * 2.0).rgb * 0.1216216216;
    c += texture(uTex, vUv + uDirection * 3.0).rgb * 0.0540540541;
    c += texture(uTex, vUv + uDirection * 4.0).rgb * 0.0162162162;
    fragColor = vec4(c, 1.0);
}

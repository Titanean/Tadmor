#version 300 es
precision highp float;

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uHdr;
uniform float uThreshold;  // luminance above which bloom kicks in (LDR, 0..1)
uniform float uSoftKnee;   // 0 = hard cutoff, 1 = very soft

// Reinhard luminance-preserving tonemap (matches scene shaders and composite).
vec3 tonemap(vec3 c) {
    float m = max(max(c.r, c.g), c.b);
    if (m <= 0.0) return vec3(0.0);
    float mapped = m / (1.0 + m);
    return c * (mapped / m);
}

// Extract bright pixels AFTER tonemapping. The scene is written in linear HDR
// (exposed but un-tonemapped) so we can do a single Reinhard pass here and read
// values in [0, 1]. Threshold ~0.7–0.9 then catches hot regions (lit photosphere,
// emissive granulation, lava) without sweeping the whole planet.
void main() {
    vec3 hdr = texture(uHdr, vUv).rgb;
    vec3 ldr = tonemap(hdr);
    float brightness = max(max(ldr.r, ldr.g), ldr.b);

    // Soft-knee curve (Unity / UE-style) — quadratic ramp inside the knee
    float knee = uThreshold * uSoftKnee + 1e-5;
    float soft = clamp(brightness - uThreshold + knee, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee + 1e-5);
    float contribution = max(brightness - uThreshold, soft) / max(brightness, 1e-5);

    fragColor = vec4(ldr * contribution, 1.0);
}

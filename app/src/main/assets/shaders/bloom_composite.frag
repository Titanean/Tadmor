#version 300 es
precision highp float;

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uHdr;
uniform sampler2D uBloom;
uniform float uIntensity;  // additive bloom strength

// Reinhard luminance-preserving tonemap (matches planet.frag / star_globe.frag).
vec3 tonemap(vec3 c) {
    float m = max(max(c.r, c.g), c.b);
    if (m <= 0.0) return vec3(0.0);
    float mapped = m / (1.0 + m);
    return c * (mapped / m);
}

void main() {
    // Scene is written as linear HDR (exposed, un-tonemapped).
    // Bloom texture was extracted in LDR space (already tonemapped).
    // Workflow: tonemap the HDR once here, add the LDR bloom additively,
    // then encode to sRGB. This matches the extract pass so brightness
    // levels agree and we avoid double-tonemapping the bloom contribution.
    vec3 hdr = texture(uHdr, vUv).rgb;
    vec3 bloom = texture(uBloom, vUv).rgb;
    vec3 ldr = tonemap(hdr);
    vec3 c = ldr + bloom * uIntensity;
    c = pow(c, vec3(1.0 / 2.2));  // linear → sRGB
    fragColor = vec4(c, 1.0);
}

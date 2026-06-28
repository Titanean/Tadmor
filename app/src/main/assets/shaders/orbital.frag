#version 300 es

precision highp float;

in vec3 vColor;
in float vAlpha;

uniform int uRenderMode;  // 0 = star glow, 1 = line, 2 = fill, 3 = planet, 4 = star core
uniform float uLayerAlpha;  // multiplier for fades (HZ / starfield toggles)

out vec4 fragColor;

void main() {
    if (uRenderMode == 1) {
        // Lines: straight color with alpha
        fragColor = vec4(vColor, 1.0);
        return;
    }

    if (uRenderMode == 2) {
        // Fill (HZ annulus): use vertex color at partial opacity, scaled by fade
        fragColor = vec4(vColor, 0.45 * uLayerAlpha);
        return;
    }

    // Points
    vec2 pc = gl_PointCoord * 2.0 - 1.0;
    float dist = dot(pc, pc);
    if (dist > 1.0) discard;

    if (uRenderMode == 3) {
        // Flat solid circle for planets — alpha driven by per-vertex fade
        if (vAlpha <= 0.0) discard;
        fragColor = vec4(vColor, vAlpha);
        return;
    }

    if (uRenderMode == 4) {
        // Star core only — opaque disc, discard glow region (for depth pre-pass)
        if (dist > 0.36) discard;
        float core = exp(-dist * 3.0);
        fragColor = vec4(vColor + vColor * core * 0.3, 1.0);
        return;
    }

    // Mode 0: star — opaque core with outer glow halo
    // Inner 60%: fully opaque with bright core
    // Outer 40%: glow fading to transparent
    float core = exp(-dist * 3.0);
    vec3 color = vColor + vColor * core * 0.3;

    float alpha;
    if (dist < 0.36) {
        // Inside the solid disc (sqrt(0.36) = 0.6 radius)
        alpha = 1.0;
    } else {
        // Glow halo fading out
        alpha = 1.0 - smoothstep(0.36, 1.0, dist);
    }

    fragColor = vec4(color, alpha);
}

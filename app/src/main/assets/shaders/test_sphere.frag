#version 300 es

precision highp float;

uniform vec3 uColor;
uniform vec3 uLightDir;
uniform vec3 uLightColor;

in vec3 vWorldPos;
in vec3 vWorldNormal;
in vec2 vTexCoord;

out vec4 fragColor;

// Returns 1.0 if inside a grid line, 0.0 otherwise
float grid(vec2 uv, float divisions, float thickness) {
    vec2 scaled = uv * divisions;
    vec2 grid = abs(fract(scaled - 0.5) - 0.5);
    vec2 line = smoothstep(thickness, thickness + 0.01, grid);
    return 1.0 - min(line.x, line.y);
}

// Segment-based digit rendering in a local [0,1]x[0,1] box.
// Each digit is drawn from 7-segment-style line checks.
float segment(vec2 p, vec2 a, vec2 b, float w) {
    vec2 pa = p - a, ba = b - a;
    float t = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return smoothstep(w + 0.01, w, length(pa - ba * t));
}

float digit(int d, vec2 p, float w) {
    float s = 0.0;
    // Segment positions for a 7-segment display in [0.1,0.9] x [0.05,0.95]
    vec2 tl = vec2(0.2, 0.95); vec2 tr = vec2(0.8, 0.95);
    vec2 ml = vec2(0.2, 0.5);  vec2 mr = vec2(0.8, 0.5);
    vec2 bl = vec2(0.2, 0.05); vec2 br = vec2(0.8, 0.05);

    if (d == 0) { s += segment(p, tl, tr, w) + segment(p, tr, mr, w) + segment(p, mr, br, w) + segment(p, br, bl, w) + segment(p, bl, ml, w) + segment(p, ml, tl, w); }
    if (d == 1) { s += segment(p, tr, mr, w) + segment(p, mr, br, w); }
    if (d == 2) { s += segment(p, tl, tr, w) + segment(p, tr, mr, w) + segment(p, ml, mr, w) + segment(p, ml, bl, w) + segment(p, bl, br, w); }
    if (d == 3) { s += segment(p, tl, tr, w) + segment(p, tr, mr, w) + segment(p, ml, mr, w) + segment(p, mr, br, w) + segment(p, bl, br, w); }
    if (d == 4) { s += segment(p, tl, ml, w) + segment(p, ml, mr, w) + segment(p, tr, mr, w) + segment(p, mr, br, w); }
    return clamp(s, 0.0, 1.0);
}

// Draw a number label (0-360) at a specific UV region
float drawLabel(vec2 uv, vec2 center, vec2 halfSize, int value) {
    vec2 local = (uv - (center - halfSize)) / (halfSize * 2.0);
    if (local.x < 0.0 || local.x > 1.0 || local.y < 0.0 || local.y > 1.0) return 0.0;

    float w = 0.06;
    int d2 = value / 100;
    int d1 = (value / 10) % 10;
    int d0 = value % 10;

    float s = 0.0;
    if (value >= 100) {
        // 3-digit: split into thirds
        vec2 p0 = vec2(local.x * 3.0, local.y);
        vec2 p1 = vec2(local.x * 3.0 - 1.0, local.y);
        vec2 p2 = vec2(local.x * 3.0 - 2.0, local.y);
        s += digit(d2, p0, w) + digit(d1, p1, w) + digit(d0, p2, w);
    } else if (value >= 10) {
        // 2-digit
        vec2 p0 = vec2(local.x * 2.0, local.y);
        vec2 p1 = vec2(local.x * 2.0 - 1.0, local.y);
        s += digit(d1, p0, w) + digit(d0, p1, w);
    } else {
        s += digit(d0, local, w);
    }
    return clamp(s, 0.0, 1.0);
}

void main() {
    float ambient = 0.05;
    vec3 N = normalize(vWorldNormal);
    vec3 L = normalize(uLightDir);
    float diff = max(dot(N, L), 0.0);
    vec3 baseColor = uColor * (ambient + diff * uLightColor);

    // Grid: 12 longitude lines (every 30°), 6 latitude lines (every 30°)
    float gridLine = grid(vTexCoord, 12.0, 0.015);

    // Equator highlight (thicker, brighter)
    float eqDist = abs(vTexCoord.y - 0.5);
    float equator = smoothstep(0.005, 0.002, eqDist);

    // Prime meridian highlight (u = 0)
    float pmDist = min(vTexCoord.x, 1.0 - vTexCoord.x);
    float meridian = smoothstep(0.005, 0.002, pmDist);

    // Degree labels at equator for 0°, 90°, 180°, 270°
    float label = 0.0;
    vec2 labelSize = vec2(0.04, 0.05);
    label += drawLabel(vTexCoord, vec2(0.0,  0.45), labelSize, 0);
    label += drawLabel(vTexCoord, vec2(1.0,  0.45), labelSize, 0);   // wrap
    label += drawLabel(vTexCoord, vec2(0.25, 0.45), labelSize, 90);
    label += drawLabel(vTexCoord, vec2(0.5,  0.45), labelSize, 180);
    label += drawLabel(vTexCoord, vec2(0.75, 0.45), labelSize, 270);
    label = clamp(label, 0.0, 1.0);

    // Pole markers: small bright spots at top/bottom
    float northPole = smoothstep(0.02, 0.01, vTexCoord.y);
    float southPole = smoothstep(0.98, 0.99, vTexCoord.y);

    // Compose: grid in subtle white, equator/meridian brighter, labels white
    vec3 gridColor = vec3(1.0, 1.0, 1.0);
    vec3 eqColor = vec3(1.0, 0.85, 0.4);   // Gold for equator
    vec3 pmColor = vec3(1.0, 0.4, 0.3);     // Red for prime meridian
    vec3 poleColor = vec3(0.4, 0.8, 1.0);   // Cyan for poles

    vec3 color = baseColor;
    color = mix(color, gridColor, gridLine * 0.3);
    color = mix(color, eqColor, equator * 0.7);
    color = mix(color, pmColor, meridian * 0.7);
    color = mix(color, gridColor, label * 0.9);
    color = mix(color, poleColor, (northPole + southPole) * 0.8);

    fragColor = vec4(color, 1.0);
}

#version 300 es

precision highp float;

in vec3 vColor;
in float vFog;
in float vAlpha;

uniform float uTime;
uniform int uIsLine;
uniform float uLineAlpha;

const vec3 BG_COLOR = vec3(0.024, 0.031, 0.059);  // matches ExoColors.background #06080F

out vec4 fragColor;

void main() {
    if (uIsLine == 1) {
        // Lines: solid color, faded by depth fog toward background
        vec3 fogged = mix(vColor, BG_COLOR, vFog);
        float alpha = (1.0 - vFog) * uLineAlpha;  // fog fade + crossfade
        fragColor = vec4(fogged, alpha);
        return;
    }

    // Points: fully fade out (including discard) when filter hides the star
    if (vAlpha <= 0.0) discard;

    // Circular point with soft edge
    vec2 pc = gl_PointCoord * 2.0 - 1.0;
    float dist = dot(pc, pc);
    if (dist > 1.0) discard;

    float softEdge = 1.0 - smoothstep(0.5, 1.0, dist);

    vec3 color = vColor;

    // Additive glow core
    float core = exp(-dist * 3.0);
    color += vColor * core * 0.3;

    fragColor = vec4(color, softEdge * vAlpha);
}

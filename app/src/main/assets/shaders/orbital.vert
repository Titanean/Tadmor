#version 300 es

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aColor;
layout(location = 2) in float aSize;
layout(location = 3) in float aAlpha;

uniform mat4 uMVP;
uniform float uScreenHeight;

out vec3 vColor;
out float vAlpha;

void main() {
    gl_Position = uMVP * vec4(aPosition, 1.0);

    // Point size: scale by depth and screen height
    float depthScale = clamp(2.0 / (1.0 + gl_Position.z * 0.5), 0.3, 3.0);
    gl_PointSize = max(aSize * depthScale * (uScreenHeight / 800.0), 1.0);

    vColor = aColor;
    vAlpha = aAlpha;
}

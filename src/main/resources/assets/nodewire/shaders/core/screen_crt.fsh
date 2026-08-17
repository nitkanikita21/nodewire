#version 150

// Nodewire CRT screen look (clean-room, classic techniques): barrel
// curvature, per-texel scanlines, phosphor triads, edge deconvergence,
// vignette, mild color grade and a subtle mains-hum flicker. Applied to
// every video feed drawn on a Screen block; the separate screen_noise
// shader still takes over when the signal degrades.

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float Time;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec2 uv = texCoord0;

    // Barrel curvature: push UVs outward quadratically from the centre.
    vec2 c = uv * 2.0 - 1.0;
    float r2 = dot(c, c);
    c *= 1.0 + 0.025 * r2;
    uv = c * 0.5 + 0.5;
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        // Tube bezel outside the curved picture.
        fragColor = vec4(0.02, 0.02, 0.025, vertexColor.a) * ColorModulator;
        return;
    }

    vec2 texSize = vec2(textureSize(Sampler0, 0));

    // No deconvergence: splitting the channels is what a real tube does, but
    // a feed is only 256px and is displayed large, so even a sub-texel split
    // turned every high-contrast edge — a treeline against the sky — into
    // saturated red and magenta fringes instead of a soft glow.
    vec3 col = texture(Sampler0, uv).rgb;

    // Scanlines: one dark line per texel row.
    float line = sin(uv.y * texSize.y * 3.14159265);
    col *= 0.93 + 0.07 * line * line;

    // Phosphor triads: R/G/B-tinted vertical stripes, three per texel.
    float px = floor(uv.x * texSize.x * 3.0);
    int ph = int(mod(px, 3.0));
    vec3 mask = ph == 0 ? vec3(1.03, 0.985, 0.985)
              : ph == 1 ? vec3(0.985, 1.03, 0.985)
                        : vec3(0.985, 0.985, 1.03);
    col *= mask;

    // Mild grade: lift first — a CRT screen glows, and the passes below only
    // ever subtract (scanlines, phosphor mask, vignette), which left dusk
    // scenes almost unreadable — then a touch of contrast.
    col = clamp(col * 1.25, 0.0, 1.0);
    col = clamp((col - 0.5) * 1.06 + 0.5, 0.0, 1.0);
    float luma = dot(col, vec3(0.299, 0.587, 0.114));
    col = clamp(mix(vec3(luma), col, 1.05), 0.0, 1.0);

    // Vignette toward the tube corners.
    col *= 1.0 - 0.08 * r2;

    // Subtle mains-hum flicker.
    col *= 0.99 + 0.01 * sin(Time * 90.0);

    fragColor = vec4(col * vertexColor.rgb, vertexColor.a) * ColorModulator;
}

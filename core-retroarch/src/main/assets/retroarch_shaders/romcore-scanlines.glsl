// romcore-scanlines.glsl -- light scanlines for RomCore's 2D systems: crisp pixels plus darker gaps between source lines.
// Copyright (C) RomCore authors. Licensed under the GNU GPL v3 or later, like the rest of RomCore.
// Written from scratch for RomCore. Sharp-bilinear horizontally (like romcore-sharp.glsl) and a cosine scanline profile
// vertically. No mask, no bloom, no curvature: a flat picture. Two texture taps' worth of work per pixel (one).

// Scanline depth, 0 = off .. 1 = black gaps.
#define SCANLINE_STRENGTH 0.40
// Output pixels over which neighbouring source pixels blend horizontally.
#define EDGE_SOFT_PX 1.5
// Gain that gives back most of the brightness the gaps take away.
#define BRIGHTNESS_COMP 1.10
// Scanlines fade out when a source line covers fewer than LINE_FADE_PX output pixels (avoids moire), fully on above LINE_FULL_PX.
#define LINE_FADE_PX 2.0
#define LINE_FULL_PX 4.0

#if defined(VERTEX)

attribute vec4 VertexCoord;
attribute vec4 TexCoord;
uniform mat4 MVPMatrix;
varying vec2 vTex;

void main()
{
   gl_Position = MVPMatrix * VertexCoord;
   vTex = TexCoord.xy;
}

#elif defined(FRAGMENT)

#ifdef GL_ES
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
#endif

uniform sampler2D Texture;
uniform vec2 OutputSize;
uniform vec2 TextureSize;
uniform vec2 InputSize;
varying vec2 vTex;

const float PI = 3.14159265;

void main()
{
   vec2 p = vTex * TextureSize;
   float ppx = OutputSize.x / InputSize.x;
   float ppl = OutputSize.y / InputSize.y;

   float xi = floor(p.x);
   float r = p.x - xi - 0.5;
   float k = clamp(ppx / EDGE_SOFT_PX, 1.0, 32.0);
   float h = 0.5 - 0.5 / k;
   float qx = xi + 0.5 + (r - clamp(r, -h, h)) * k;
   float qy = floor(p.y) + 0.5;                           // every output row samples the centre of its source line
   vec2 q = clamp(vec2(qx, qy), vec2(0.5), InputSize - vec2(0.5));
   vec3 col = texture2D(Texture, q / TextureSize).rgb;

   float fade = smoothstep(LINE_FADE_PX, LINE_FULL_PX, ppl);
   float d = fract(p.y) - 0.5;                            // distance from the line centre, in lines
   float f = 1.0 / ppl;                                   // output pixel footprint, in lines
   float a1 = sin(PI * f) / (PI * f);                     // box-filter attenuation of the profile's harmonic (no moire)
   float prof = 1.0 + SCANLINE_STRENGTH * fade * a1 * cos(2.0 * PI * d);
   gl_FragColor = vec4(clamp(col * prof * mix(1.0, BRIGHTNESS_COMP, fade), 0.0, 1.0), 1.0);
}

#endif

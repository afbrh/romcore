// romcore-crt.glsl -- flat CRT look for RomCore (NES / SNES): scanlines, aperture-grille mask, soft beam, light bloom.
// Copyright (C) RomCore authors. Licensed under the GNU GPL v3 or later, like the rest of RomCore.
// Written from scratch for RomCore (not derived from any third-party shader source). The general ideas (scanline beam
// profile, RGB phosphor stripes, brightness-dependent beam width) are the common public CRT-emulation techniques.
//
// Libretro "GLSL" single-file format: one file, #if defined(VERTEX) / #elif defined(FRAGMENT), run by RetroArch's GL driver.
// RetroArch prepends "#define VERTEX" or "#define FRAGMENT" and no version line (this file has none on purpose: it is
// GLSL ES 1.00 / GLSL 1.10 style, which every GLES 2/3 driver accepts).
//
// FLAT IMAGE: there is NO curvature, corner rounding, vignette, bezel or edge darkening. Every output pixel gets the
// same treatment, so the picture stays a plain rectangle.
//
// Cost: 5 texture taps per pixel, no loops, no dependent reads, no temporal state (the output depends only on the current frame).

// ---- Tunables (edit and rebuild the app; each is a plain constant) -------------------------------------------------
// Scanline depth, 0 = off .. 1 = fully black gaps (before the brightness-dependent beam widening below).
#define SCANLINE_STRENGTH 0.55
// How much bright pixels widen the beam and fill the gap between lines (like a real CRT), 0..1.
#define BEAM_WIDEN 0.55
// RGB aperture-grille stripes, 0 = off .. 1 = very strong. 0.35 is subtle at 1080p (3 output pixels per RGB triad).
#define MASK_STRENGTH 0.35
// Horizontal beam softness, 0 = crisp pixels .. 1 = very soft.
#define BEAM_SOFTNESS 0.35
// Output pixels over which neighbouring source pixels blend (sharp-bilinear edge width). Smaller = crisper.
#define EDGE_SOFT_PX 2.0
// Glow from bright areas, 0 = off .. ~0.3 = strong.
#define BLOOM 0.14
// Overall gain that compensates the energy lost to scanline gaps and mask (keeps the picture within ~10% of the
// unfiltered brightness on typical content).
#define BRIGHTNESS_COMP 1.06
// Scanlines fade out when a source line covers fewer than this many / this many output pixels (hi-res / interlaced
// bsnes frames with 448 lines, or any very low output size) to avoid moire. Fully on above LINE_FULL_PX.
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
uniform vec2 OutputSize;    // size of the viewport this pass renders into, in pixels
uniform vec2 TextureSize;   // size of the source texture (may be larger than the picture)
uniform vec2 InputSize;     // size of the picture inside the texture (e.g. 256x240, 256x224, 512x448)
varying vec2 vTex;

const float PI = 3.14159265;
const vec3 LUMA = vec3(0.299, 0.587, 0.114);

// Source colour -> linear light (gamma 2.0 approximation: cheap and close enough for 8-bit game video).
vec3 toLinear(vec3 c) { return c * c; }

vec3 tap(vec2 posTexels, vec2 lo, vec2 hi)
{
   // Clamp to the picture area so taps never read the unused part of an oversized texture.
   return toLinear(texture2D(Texture, clamp(posTexels, lo, hi) / TextureSize).rgb);
}

void main()
{
   vec2 p = vTex * TextureSize;                       // position in source texels
   vec2 lo = vec2(0.5);
   vec2 hi = InputSize - vec2(0.5);

   float ppx = OutputSize.x / InputSize.x;            // output pixels per source pixel (horizontal)
   float ppl = OutputSize.y / InputSize.y;            // output pixels per source line (vertical)

   // ---- horizontal: sharp-bilinear centre tap, so pixels stay crisp at non-integer scales -------------------------
   float xi = floor(p.x);
   float r = p.x - xi - 0.5;                          // -0.5 .. 0.5 across the source pixel
   float k = clamp(ppx / EDGE_SOFT_PX, 1.0, 32.0);
   float h = 0.5 - 0.5 / k;
   float qx = xi + 0.5 + (r - clamp(r, -h, h)) * k;
   float line = floor(p.y);
   float qy = line + 0.5;                             // every output row samples the centre of its source line

   // ---- beam: centre + two side taps (horizontal softness) ---------------------------------------------------------
   float side = 0.5 * BEAM_SOFTNESS;
   vec3 c0 = tap(vec2(qx, qy), lo, hi);
   vec3 c1 = tap(vec2(qx - 0.6, qy), lo, hi);
   vec3 c2 = tap(vec2(qx + 0.6, qy), lo, hi);
   vec3 beam = c0 * (1.0 - 2.0 * side) + (c1 + c2) * side;

   // ---- scanlines: periodic beam profile, analytically box-filtered over the output pixel (no moire) -------------
   float lum = dot(beam, LUMA);
   float fade = smoothstep(LINE_FADE_PX, LINE_FULL_PX, ppl);
   float s = SCANLINE_STRENGTH * fade * (1.0 - BEAM_WIDEN * min(lum, 1.0));
   float d = fract(p.y) - 0.5;                        // distance from the line centre, in lines (-0.5 .. 0.5)
   float f = 1.0 / ppl;                               // output pixel footprint, in lines
   float a1 = sin(PI * f) / (PI * f);                 // attenuation of the 1st / 2nd harmonic by the pixel box filter
   float a2 = max(sin(2.0 * PI * f) / (2.0 * PI * f), 0.0);
   float cs = cos(2.0 * PI * d);
   float prof = 1.0 + s * (1.3 * a1 * cs + 0.3 * a2 * (2.0 * cs * cs - 1.0));   // mean 1, minimum 1 - s, peak 1 + 1.6 s
   vec3 col = beam * prof;

   // ---- bloom: two wide taps at the un-snapped row (they blend neighbouring lines and fill the gaps) --------------
   vec3 g1 = tap(vec2(p.x - 2.0, p.y), lo, hi);
   vec3 g2 = tap(vec2(p.x + 2.0, p.y), lo, hi);
   vec3 glow = (g1 + g2 + beam) * (1.0 / 3.0);
   float b = BLOOM * (0.4 + 0.6 * min(dot(glow, LUMA) * 2.0, 1.0));
   col = mix(col, glow, b);

   // ---- aperture grille: RGB stripes, 3 output pixels per triad, brightness-normalised ------------------------------
   float ph = mod(floor(gl_FragCoord.x), 3.0);
   vec3 dist = abs(vec3(ph) - vec3(0.0, 1.0, 2.0));
   dist = min(dist, 3.0 - dist);                      // 0 on the channel's own pixel, 1 on the other two
   vec3 stripe = 1.0 - MASK_STRENGTH * 0.5 * dist;    // own pixel 1.0, others 1 - M/2
   col *= stripe / (1.0 - MASK_STRENGTH / 3.0);       // mean of the mask is 1 - M/3

   col *= BRIGHTNESS_COMP;
   gl_FragColor = vec4(sqrt(clamp(col, 0.0, 1.0)), 1.0);
}

#endif

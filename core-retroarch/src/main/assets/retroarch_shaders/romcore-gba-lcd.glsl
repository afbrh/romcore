// romcore-gba-lcd.glsl -- classic Game Boy Advance LCD look for RomCore: visible pixel grid, faint RGB sub-pixel
// stripes, the original screen's washed-out colours and slightly lifted black level.
// Copyright (C) RomCore authors. Licensed under the GNU GPL v3 or later, like the rest of RomCore.
// Written from scratch for RomCore (not derived from any third-party shader source). The ideas (grid gaps between LCD
// cells, RGB stripes, a colour-mixing matrix + gamma mismatch to imitate the unlit reflective panel) are common public
// handheld-LCD emulation techniques.
//
// Libretro "GLSL" single-file format (see romcore-crt.glsl): no version directive, GLSL ES 1.00 / GLSL 1.10 style. Keep that directive's name out of comments: RetroArch scans the whole text for it.
// FLAT IMAGE: no curvature, vignette or bezel. Cost: 1 texture tap per pixel, no loops, no temporal state.

// ---- Tunables (edit and rebuild the app; each is a plain constant) -------------------------------------------------
// Width of the dark gap between LCD cells, as a fraction of one source pixel (0 = no grid). 0.16 = about 1 output pixel at 1080p.
#define GRID_WIDTH 0.17
// Brightness left in the gaps (0 = black, 1 = same as the pixel). Real GBA gaps are dark grey, not black.
#define GAP_LEVEL 0.32
// Brightness boost inside the cells that makes up for the gap darkness, so the overall picture stays about as bright.
#define CELL_GAIN 1.14
// Faint vertical RGB stripes inside each cell, 0 = off .. 0.3 = strong.
#define STRIPE_STRENGTH 0.10
// Gamma the game was authored for, and the (lower) gamma of the panel; the mismatch is what flattens the contrast.
#define TARGET_GAMMA 2.2
#define DISPLAY_GAMMA 1.9
// Overall luminance of the panel (the original screen is unlit and fairly dim).
#define PANEL_LUM 1.0
// Colour saturation after mixing: 1 = unchanged, lower = more washed out.
#define SATURATION 0.88
// Black level lift (the "off" LCD is dark grey-green, not black).
#define BLACK_LIFT 0.05
// Output pixels over which neighbouring source pixels blend (sharp-bilinear edge width). Smaller = crisper.
#define EDGE_SOFT_PX 1.5
// Grid and stripes fade out below / above these output pixels per source pixel (avoids moire at small sizes).
#define DETAIL_FADE_PX 2.5
#define DETAIL_FULL_PX 5.0

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
uniform vec2 InputSize;     // size of the picture inside the texture (240x160 for the GBA)
varying vec2 vTex;

const vec3 LUMA = vec3(0.299, 0.587, 0.114);

// Sharp-bilinear snap along one axis: keeps source pixels crisp at non-integer scales, blending only at their edges.
float snapAxis(float p, float ppx)
{
   float i = floor(p);
   float r = p - i - 0.5;
   float k = clamp(ppx / EDGE_SOFT_PX, 1.0, 32.0);
   float h = 0.5 - 0.5 / k;
   return i + 0.5 + (r - clamp(r, -h, h)) * k;
}

// Coverage of the lit cell along one axis: 1 inside, 0 in the gap, box-filtered over one output pixel (fw, in source pixels).
float cellCover(float f, float fw)
{
   float edge = 0.5 - GRID_WIDTH * 0.5;
   return 1.0 - smoothstep(edge - fw, edge + fw, abs(f));
}

void main()
{
   vec2 p = vTex * TextureSize;                       // position in source texels
   vec2 ppx = OutputSize / InputSize;                 // output pixels per source pixel (x, y)

   // ---- picture: sharp-bilinear sample, clamped to the picture area -------------------------------------------------
   vec2 q = vec2(snapAxis(p.x, ppx.x), snapAxis(p.y, ppx.y));
   q = clamp(q, vec2(0.5), InputSize - vec2(0.5));
   vec3 c = texture2D(Texture, q / TextureSize).rgb;

   // ---- panel colour: game gamma -> linear, wash out + cross-mix the channels, dim, re-encode with the panel gamma -----
   vec3 lin = pow(max(c, vec3(0.0)), vec3(TARGET_GAMMA));
   float l = dot(lin, LUMA);
   lin = mix(vec3(l), lin, SATURATION);
   // The sub-pixels leak into each other a little (rows: output R, G, B; each row sums to ~1).
   vec3 mixed = vec3(dot(lin, vec3(0.84, 0.11, 0.05)),
                     dot(lin, vec3(0.09, 0.82, 0.09)),
                     dot(lin, vec3(0.05, 0.15, 0.80)));
   mixed = clamp(mixed * PANEL_LUM, 0.0, 1.0);
   vec3 col = pow(mixed, vec3(1.0 / DISPLAY_GAMMA));
   col = mix(vec3(BLACK_LIFT * 0.9, BLACK_LIFT, BLACK_LIFT * 0.8), col, 1.0 - BLACK_LIFT);

   // ---- LCD cells: grid gaps + RGB stripes, faded out when a source pixel is only a few output pixels wide ------------
   vec2 f = fract(p) - 0.5;                           // -0.5 .. 0.5 across the source pixel
   vec2 fw = 1.0 / ppx;                               // one output pixel, in source pixels
   float detail = smoothstep(DETAIL_FADE_PX, DETAIL_FULL_PX, min(ppx.x, ppx.y));
   float body = cellCover(f.x, fw.x) * cellCover(f.y, fw.y);
   float cell = mix(1.0, mix(GAP_LEVEL, CELL_GAIN, body), detail);
   float third = floor((f.x + 0.5) * 3.0);            // 0, 1, 2 = which colour stripe of the cell this output pixel is in
   vec3 stripe = vec3(1.0) - STRIPE_STRENGTH * detail * (vec3(1.0) - vec3(float(third < 0.5), float(abs(third - 1.0) < 0.5), float(third > 1.5)));
   col *= cell * stripe;

   gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}

#endif

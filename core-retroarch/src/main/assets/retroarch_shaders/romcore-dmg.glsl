// romcore-dmg.glsl -- original Game Boy (DMG) green LCD look for RomCore: four olive-green shades, the dark grid
// between pixels, and the soft drop shadow the old STN panel cast to the lower right of dark pixels.
// Copyright (C) RomCore authors. Licensed under the GNU GPL v3 or later, like the rest of RomCore.
// Written from scratch for RomCore (not derived from any third-party shader source).
//
// Libretro "GLSL" single-file format (see romcore-crt.glsl): no version directive, GLSL ES 1.00 / GLSL 1.10 style. Keep that directive's name out of comments: RetroArch scans the whole text for it.
// FLAT IMAGE: no curvature, vignette or bezel. Cost: 3 texture taps per pixel, no loops, no temporal state.
// Any colour the core produces (mGBA colourises some GB games) is reduced to its brightness, then to the 4 DMG shades.

// ---- Tunables (edit and rebuild the app; each is a plain constant) -------------------------------------------------
// The four panel shades, lightest to darkest.
#define SHADE0 vec3(0.675, 0.745, 0.365)
#define SHADE1 vec3(0.510, 0.600, 0.300)
#define SHADE2 vec3(0.290, 0.390, 0.220)
#define SHADE3 vec3(0.110, 0.185, 0.130)
// Width of the grid line between pixels, as a fraction of one source pixel (0 = no grid).
#define GRID_WIDTH 0.16
// How much of the neighbouring shade darkens the grid line (0 = invisible, 1 = as dark as the pixel next to it).
#define GRID_DARKNESS 0.30
// Strength of the drop shadow cast right/down of dark pixels (0 = none).
#define SHADOW_STRENGTH 0.16
// Output pixels over which neighbouring source pixels blend (sharp-bilinear edge width). Smaller = crisper.
#define EDGE_SOFT_PX 1.5
// Grid and shadow fade out below / above these output pixels per source pixel (avoids moire at small sizes).
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
uniform vec2 InputSize;     // size of the picture inside the texture (160x144 for the Game Boy)
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

// Brightness 0..1 of the texel at integer position t (outside the picture counts as the lightest shade).
float lumaAt(vec2 t)
{
   if (t.x < 0.0 || t.y < 0.0 || t.x >= InputSize.x || t.y >= InputSize.y) return 1.0;
   return dot(texture2D(Texture, (t + vec2(0.5)) / TextureSize).rgb, LUMA);
}

// Brightness -> one of the four panel shades (a short ramp at each step keeps the bilinear blend between texels clean).
vec3 shade(float l)
{
   float s = clamp(l, 0.0, 1.0) * 3.0;
   float d = floor(s + 0.5);                          // nearest of 0..3 (3 = white)
   if (d > 2.5) return SHADE0;
   if (d > 1.5) return SHADE1;
   if (d > 0.5) return SHADE2;
   return SHADE3;
}

void main()
{
   vec2 p = vTex * TextureSize;                       // position in source texels
   vec2 ppx = OutputSize / InputSize;                 // output pixels per source pixel (x, y)

   vec2 q = vec2(snapAxis(p.x, ppx.x), snapAxis(p.y, ppx.y));
   q = clamp(q, vec2(0.5), InputSize - vec2(0.5));
   vec3 col = shade(dot(texture2D(Texture, q / TextureSize).rgb, LUMA));

   vec2 t = floor(p);
   vec2 f = fract(p);                                 // 0..1 across the source pixel
   vec2 fw = 1.0 / ppx;                               // one output pixel, in source pixels
   float detail = smoothstep(DETAIL_FADE_PX, DETAIL_FULL_PX, min(ppx.x, ppx.y));

   // Drop shadow: a dark pixel to the left / above darkens the first slice of this pixel.
   float dl = 1.0 - lumaAt(t + vec2(-1.0, 0.0));
   float du = 1.0 - lumaAt(t + vec2(0.0, -1.0));
   float own = lumaAt(t);
   float sh = max(dl * (1.0 - smoothstep(0.0, 0.38, f.x)), du * (1.0 - smoothstep(0.0, 0.38, f.y)));
   col = mix(col, SHADE3, SHADOW_STRENGTH * detail * sh * own);

   // Grid line: a thin darker band along the left and top edge of every pixel.
   float gx = 1.0 - smoothstep(GRID_WIDTH * 0.5 - fw.x, GRID_WIDTH * 0.5 + fw.x, min(f.x, 1.0 - f.x));
   float gy = 1.0 - smoothstep(GRID_WIDTH * 0.5 - fw.y, GRID_WIDTH * 0.5 + fw.y, min(f.y, 1.0 - f.y));
   float grid = max(gx, gy);
   col = mix(col, col * (1.0 - GRID_DARKNESS) + SHADE3 * GRID_DARKNESS * 0.5, grid * detail);

   gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}

#endif

// romcore-sharp.glsl -- "Sharp Pixels" for RomCore's 2D systems: crisp pixels at any scale, no scanlines or mask.
// Copyright (C) RomCore authors. Licensed under the GNU GPL v3 or later, like the rest of RomCore.
// Written from scratch for RomCore. Technique: sharp-bilinear sampling -- inside a source pixel the colour is flat, and only
// a narrow band at the pixel edge blends with the neighbour, so non-integer scaling does not blur or shimmer.
//
// Libretro "GLSL" single-file format (see romcore-crt.glsl). One texture tap per output pixel.

// Output pixels over which neighbouring source pixels blend. Smaller = crisper, larger = softer.
#define EDGE_SOFT_PX 1.5

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

vec2 snap(vec2 p, vec2 ppx)
{
   vec2 i = floor(p);
   vec2 r = p - i - 0.5;                                  // -0.5 .. 0.5 across the source pixel
   vec2 k = clamp(ppx / EDGE_SOFT_PX, vec2(1.0), vec2(32.0));
   vec2 h = 0.5 - 0.5 / k;
   return i + 0.5 + (r - clamp(r, -h, h)) * k;
}

void main()
{
   vec2 p = vTex * TextureSize;                           // position in source texels
   vec2 ppx = OutputSize / InputSize;                     // output pixels per source pixel (x, y)
   vec2 q = clamp(snap(p, ppx), vec2(0.5), InputSize - vec2(0.5));
   gl_FragColor = vec4(texture2D(Texture, q / TextureSize).rgb, 1.0);
}

#endif

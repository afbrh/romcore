// romcore-gbcrop.glsl -- cuts the Super Game Boy border off a Game Boy frame for RomCore.
// Copyright (C) RomCore authors. Licensed under the GNU GPL v3 or later, like the rest of RomCore.
//
// mGBA hands over a 256x224 frame (border + the 160x144 screen at offset 48,40) for Super Game Boy games and a plain 160x144 frame
// for the rest. This pass outputs only the screen: nearest-neighbour, no other change. Libretro "GLSL" single-file format (see
// romcore-crt.glsl): no version directive anywhere in the text, GLSL ES 1.00 / GLSL 1.10 style.

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
uniform vec2 TextureSize;   // size of the source texture (may be larger than the picture)
uniform vec2 InputSize;     // size of the frame inside the texture (160x144, or 256x224 with the border)
varying vec2 vTex;

void main()
{
   bool border = InputSize.x > 200.0;
   vec2 pic = border ? vec2(160.0, 144.0) : InputSize;
   vec2 off = border ? vec2(48.0, 40.0) : vec2(0.0);
   vec2 u = vTex * TextureSize / InputSize;           // 0..1 across the frame
   gl_FragColor = vec4(texture2D(Texture, (off + u * pic) / TextureSize).rgb, 1.0);
}

#endif

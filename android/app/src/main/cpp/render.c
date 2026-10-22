// GLES 3.0 renderer: draws the newest emulator frame letterboxed into the surface with one of six
// upscaling filters:
//   0 Pixel   nearest neighbour
//   1 Sharp   sharp-bilinear (crisp pixels, smooth edges at any scale)
//   2 Smooth  bilinear
//   3 FSR     AMD FidelityFX Super Resolution 1.0: EASU upscale + RCAS sharpen
//   4 MMPX    Morgan McGuire's pixel-art magnifier (2x), then sharp-bilinear to the view
//   5 AI      Anime4K CNN x2 (S variant) applied twice (240x160 -> 480x320 -> 960x640), then EASU / bilinear
//             down to the view
//
// Battery: the expensive stages (FSR EASU, MMPX, the CNN) run only when a new emulator frame arrived, the
// filter changed or the view size changed; their results live in per-context textures and a draw without
// news only runs the cheap last pass into the (undefined after swap) back buffer. All GL objects belong to
// the current context and are forgotten and recreated by renderInit().
//
// ---------------------------------------------------------------------------------------------------------
// Third-party algorithms (each re-implemented here in GLSL ES 3.00, or embedded, under the MIT license):
//
//  * FidelityFX Super Resolution 1.0 (EASU, RCAS) - Copyright (c) 2021 Advanced Micro Devices, Inc.
//    https://github.com/GPUOpen-Effects/FidelityFX-FSR   (MIT License)
//  * MMPX Style-Preserving Pixel Art Magnification - Copyright (c) 2021 Morgan McGuire and Mara Gagiu
//    https://casual-effects.com/research/McGuire2021PixelArt/   (MIT License)
//  * Anime4K v3.2 Upscale CNN x2 (network weights and passes, see anime4k.h) - Copyright (c) 2019-2021 bloc97
//    https://github.com/bloc97/Anime4K   (MIT License)
//
//  MIT License
//
//  Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
//  associated documentation files (the "Software"), to deal in the Software without restriction, including
//  without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
//  copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the
//  following conditions: The above copyright notices and this permission notice shall be included in all
//  copies or substantial portions of the Software.
//
//  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
//  LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
//  EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
//  IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
//  THE USE OR OTHER DEALINGS IN THE SOFTWARE.
// ---------------------------------------------------------------------------------------------------------
#include <GLES3/gl3.h>
#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "render.h"
#include "anime4k.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "gbalink", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "gbalink", __VA_ARGS__)

enum { F_PIXEL, F_SHARP, F_SMOOTH, F_FSR, F_MMPX, F_AI, F_COUNT };

// Anime4K network: S = 4 hidden layers of 4 channels (cheap), M = 8 layers. S is plenty for 240x160 input
// and keeps the GPU cost around a few hundred MFLOP per frame; flip to 1 to try M.
#define AI_NET 0
#define AI_MAX_PASSES 9
#define AI_MAX_SLOTS 9
#define AI_STAGES 2

typedef struct {
	GLuint id;
	GLint uFlip, uTexSize, uOutSize, uMode, uSharp, uInSize;
	bool tried;
} Prog;

typedef struct {
	GLuint tex, fbo;
	int w, h;
} Target;

typedef struct {
	const A4KPass* passes;
	int count, slots;
} A4KNet;

static const A4KNet A4K_NETS[2] = {
	{ A4K_S, A4K_S_COUNT, A4K_S_SLOTS },
	{ A4K_M, A4K_M_COUNT, A4K_M_SLOTS },
};

typedef struct {
	Target slot[AI_MAX_SLOTS]; // 1..slots-1: hidden layers (RGBA16F, input size); slot 0 unused
	Target out;                // 2x input size, RGBA8
} A4KStage;

static struct {
	GLuint vs, vao;
	GLuint frameTex;
	int frameW, frameH;
	int viewW, viewH;

	Prog blit, easu, rcas, mmpx;
	Prog ai[AI_MAX_PASSES];
	bool aiTried;
	Target fsr;    // EASU result, view-sized
	Target mmpx2x; // MMPX result, 2x frame
	A4KStage stage[AI_STAGES];

	Prog amb;          // ambient (frosted backdrop) shader
	GLuint ambTex, ambFbo; // 120x80 mip-mapped copy of the frame, blurred by sampling a high LOD
	bool ambMips;      // mip chain is current

	bool bad[F_COUNT]; // filter could not be set up on this GPU: fall back
	bool dirty;        // heavy stages must run even without a new frame
	int lastFilter, lastW, lastH;
} R;

// ------------------------------------------------------------------------------------------- shaders

// flipY = 1: uv.y = 0 at the top of the screen (the default framebuffer), flipY = 0: uv follows the row
// order of an offscreen texture (row 0 = framebuffer y 0), so offscreen passes keep image orientation.
static const char* VERT =
	"#version 300 es\n"
	"uniform float flipY;\n"
	"out vec2 uv;\n"
	"void main() {\n"
	"  vec2 p = vec2((gl_VertexID & 1) == 0 ? -1.0 : 1.0, (gl_VertexID & 2) == 0 ? -1.0 : 1.0);\n"
	"  uv = vec2(p.x * 0.5 + 0.5, mix(p.y * 0.5 + 0.5, 0.5 - p.y * 0.5, flipY));\n"
	"  gl_Position = vec4(p, 0.0, 1.0);\n"
	"}\n";

// 0 = pixel (nearest), 1 = sharp (crisp pixels, smooth edges at any scale), 2 = smooth (bilinear)
static const char* FRAG_BLIT =
	"#version 300 es\n"
	"precision highp float;\n"
	"in vec2 uv;\n"
	"out vec4 color;\n"
	"uniform sampler2D tex;\n"
	"uniform vec2 texSize;\n"
	"uniform vec2 outSize;\n"
	"uniform int filterMode;\n"
	"void main() {\n"
	"  vec2 coord = uv;\n"
	"  if (filterMode == 0) {\n"
	"    coord = (floor(uv * texSize) + 0.5) / texSize;\n"
	"  } else if (filterMode == 1) {\n"
	"    vec2 texel = uv * texSize;\n"
	"    vec2 scale = max(floor(outSize / texSize), vec2(1.0));\n"
	"    vec2 region = 0.5 - 0.5 / scale;\n"
	"    vec2 f = fract(texel) - 0.5;\n"
	"    vec2 off = (f - clamp(f, -region, region)) * scale + 0.5;\n"
	"    coord = (floor(texel) + off) / texSize;\n"
	"  }\n"
	"  color = vec4(texture(tex, coord).rgb, 1.0);\n"
	"}\n";

// FSR 1.0 EASU (edge-adaptive spatial upsampling), float path of ffx_fsr1.h ported to GLSL ES 3.00 with
// texelFetch instead of gather. pp is the sample position in input-texel space (texel centres on integers).
static const char* FRAG_EASU =
	"#version 300 es\n"
	"precision highp float;\n"
	"precision highp sampler2D;\n"
	"in vec2 uv;\n"
	"out vec4 color;\n"
	"uniform sampler2D tex;\n"
	"uniform vec2 texSize;\n"
	"vec3 F(ivec2 p, ivec2 mx) { return texelFetch(tex, clamp(p, ivec2(0), mx), 0).rgb; }\n"
	"float Lm(vec3 c) { return c.b * 0.5 + (c.r * 0.5 + c.g); }\n"
	"void setF(inout vec2 dir, inout float len, float w, float lA, float lB, float lC, float lD, float lE) {\n"
	"  float lenX = max(abs(lD - lC), abs(lC - lB));\n"
	"  float dirX = lD - lB;\n"
	"  dir.x += dirX * w;\n"
	"  lenX = clamp(abs(dirX) / max(lenX, 1e-7), 0.0, 1.0);\n"
	"  lenX *= lenX;\n"
	"  len += lenX * w;\n"
	"  float lenY = max(abs(lE - lC), abs(lC - lA));\n"
	"  float dirY = lE - lA;\n"
	"  dir.y += dirY * w;\n"
	"  lenY = clamp(abs(dirY) / max(lenY, 1e-7), 0.0, 1.0);\n"
	"  lenY *= lenY;\n"
	"  len += lenY * w;\n"
	"}\n"
	"void tapF(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {\n"
	"  vec2 v;\n"
	"  v.x = off.x * dir.x + off.y * dir.y;\n"
	"  v.y = off.x * -dir.y + off.y * dir.x;\n"
	"  v *= len;\n"
	"  float d2 = min(v.x * v.x + v.y * v.y, clp);\n"
	"  float wB = 0.4 * d2 - 1.0;\n"
	"  float wA = lob * d2 - 1.0;\n"
	"  wB *= wB;\n"
	"  wA *= wA;\n"
	"  wB = 25.0 / 16.0 * wB - (25.0 / 16.0 - 1.0);\n"
	"  float w = wB * wA;\n"
	"  aC += c * w;\n"
	"  aW += w;\n"
	"}\n"
	"void main() {\n"
	"  ivec2 mx = ivec2(texSize) - 1;\n"
	"  vec2 pp = uv * texSize - 0.5;\n"
	"  vec2 fpf = floor(pp);\n"
	"  pp -= fpf;\n"
	"  ivec2 fp = ivec2(fpf);\n"
	"  vec3 b = F(fp + ivec2(0, -1), mx), c = F(fp + ivec2(1, -1), mx);\n"
	"  vec3 e = F(fp + ivec2(-1, 0), mx), f = F(fp, mx), g = F(fp + ivec2(1, 0), mx), h = F(fp + ivec2(2, 0), mx);\n"
	"  vec3 i = F(fp + ivec2(-1, 1), mx), j = F(fp + ivec2(0, 1), mx), k = F(fp + ivec2(1, 1), mx), l = F(fp + ivec2(2, 1), mx);\n"
	"  vec3 n = F(fp + ivec2(0, 2), mx), o = F(fp + ivec2(1, 2), mx);\n"
	"  float bL = Lm(b), cL = Lm(c), eL = Lm(e), fL = Lm(f), gL = Lm(g), hL = Lm(h);\n"
	"  float iL = Lm(i), jL = Lm(j), kL = Lm(k), lL = Lm(l), nL = Lm(n), oL = Lm(o);\n"
	"  vec2 dir = vec2(0.0);\n"
	"  float len = 0.0;\n"
	"  setF(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);\n"
	"  setF(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);\n"
	"  setF(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);\n"
	"  setF(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);\n"
	"  vec2 dir2 = dir * dir;\n"
	"  float dirR = dir2.x + dir2.y;\n"
	"  bool zro = dirR < (1.0 / 32768.0);\n"
	"  dirR = inversesqrt(max(dirR, 1e-9));\n"
	"  dirR = zro ? 1.0 : dirR;\n"
	"  dir.x = zro ? 1.0 : dir.x;\n"
	"  dir *= vec2(dirR);\n"
	"  len = len * 0.5;\n"
	"  len *= len;\n"
	"  float stretch = (dir.x * dir.x + dir.y * dir.y) / max(abs(dir.x), abs(dir.y));\n"
	"  vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);\n"
	"  float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;\n"
	"  float clp = 1.0 / lob;\n"
	"  vec3 min4 = min(min(f, g), min(j, k));\n"
	"  vec3 max4 = max(max(f, g), max(j, k));\n"
	"  vec3 aC = vec3(0.0);\n"
	"  float aW = 0.0;\n"
	"  tapF(aC, aW, vec2( 0.0, -1.0) - pp, dir, len2, lob, clp, b);\n"
	"  tapF(aC, aW, vec2( 1.0, -1.0) - pp, dir, len2, lob, clp, c);\n"
	"  tapF(aC, aW, vec2(-1.0,  1.0) - pp, dir, len2, lob, clp, i);\n"
	"  tapF(aC, aW, vec2( 0.0,  1.0) - pp, dir, len2, lob, clp, j);\n"
	"  tapF(aC, aW, vec2( 0.0,  0.0) - pp, dir, len2, lob, clp, f);\n"
	"  tapF(aC, aW, vec2(-1.0,  0.0) - pp, dir, len2, lob, clp, e);\n"
	"  tapF(aC, aW, vec2( 1.0,  1.0) - pp, dir, len2, lob, clp, k);\n"
	"  tapF(aC, aW, vec2( 2.0,  1.0) - pp, dir, len2, lob, clp, l);\n"
	"  tapF(aC, aW, vec2( 2.0,  0.0) - pp, dir, len2, lob, clp, h);\n"
	"  tapF(aC, aW, vec2( 1.0,  0.0) - pp, dir, len2, lob, clp, g);\n"
	"  tapF(aC, aW, vec2( 1.0,  2.0) - pp, dir, len2, lob, clp, o);\n"
	"  tapF(aC, aW, vec2( 0.0,  2.0) - pp, dir, len2, lob, clp, n);\n"
	"  vec3 pix = min(max4, max(min4, aC / aW));\n"
	"  color = vec4(clamp(pix, 0.0, 1.0), 1.0);\n"
	"}\n";

// FSR 1.0 RCAS (robust contrast-adaptive sharpening), 5 taps, without the optional denoise. The input is
// the EASU result at exactly the output size, so integer texel fetches map 1:1 onto screen pixels.
static const char* FRAG_RCAS =
	"#version 300 es\n"
	"precision highp float;\n"
	"precision highp sampler2D;\n"
	"in vec2 uv;\n"
	"out vec4 color;\n"
	"uniform sampler2D tex;\n"
	"uniform vec2 texSize;\n"
	"uniform float sharp;\n"
	"vec3 F(ivec2 p, ivec2 mx) { return texelFetch(tex, clamp(p, ivec2(0), mx), 0).rgb; }\n"
	"void main() {\n"
	"  ivec2 mx = ivec2(texSize) - 1;\n"
	"  ivec2 p = ivec2(uv * texSize);\n"
	"  vec3 b = F(p + ivec2(0, -1), mx), d = F(p + ivec2(-1, 0), mx), e = F(p, mx);\n"
	"  vec3 f = F(p + ivec2(1, 0), mx), h = F(p + ivec2(0, 1), mx);\n"
	"  vec3 mn4 = min(min(b, d), min(f, h));\n"
	"  vec3 mx4 = max(max(b, d), max(f, h));\n"
	"  vec3 hitMin = min(mn4, e) / (4.0 * max(mx4, vec3(1e-5)));\n"
	"  vec3 hitMax = (1.0 - max(mx4, e)) / min(4.0 * mn4 - 4.0, vec3(-1e-5));\n"
	"  vec3 lobeRGB = max(-hitMin, hitMax);\n"
	"  float lobe = max(-(0.25 - 1.0 / 16.0), min(max(lobeRGB.r, max(lobeRGB.g, lobeRGB.b)), 0.0)) * sharp;\n"
	"  vec3 pix = (lobe * (b + d + f + h) + e) / (4.0 * lobe + 1.0);\n"
	"  color = vec4(clamp(pix, 0.0, 1.0), 1.0);\n"
	"}\n";

// MMPX 2x magnifier. One invocation per output pixel; (J K / L M) are the four sub-pixels of source pixel E.
static const char* FRAG_MMPX =
	"#version 300 es\n"
	"precision highp float;\n"
	"precision highp sampler2D;\n"
	"out vec4 color;\n"
	"uniform sampler2D tex;\n"
	"uniform vec2 texSize;\n"
	"ivec2 gBase;\n"
	"ivec2 gMax;\n"
	"vec3 S(int dx, int dy) { return texelFetch(tex, clamp(gBase + ivec2(dx, dy), ivec2(0), gMax), 0).rgb; }\n"
	"bool eq(vec3 a, vec3 b) { return all(equal(a, b)); }\n"
	"float luma(vec3 c) { return c.r + c.g + c.b; }\n"
	"bool all_eq2(vec3 B, vec3 A0, vec3 A1) { return eq(B, A0) && eq(B, A1); }\n"
	"bool all_eq3(vec3 B, vec3 A0, vec3 A1, vec3 A2) { return eq(B, A0) && eq(B, A1) && eq(B, A2); }\n"
	"bool all_eq4(vec3 B, vec3 A0, vec3 A1, vec3 A2, vec3 A3) { return eq(B, A0) && eq(B, A1) && eq(B, A2) && eq(B, A3); }\n"
	"bool any_eq3(vec3 B, vec3 A0, vec3 A1, vec3 A2) { return eq(B, A0) || eq(B, A1) || eq(B, A2); }\n"
	"bool none_eq2(vec3 B, vec3 A0, vec3 A1) { return !eq(B, A0) && !eq(B, A1); }\n"
	"bool none_eq4(vec3 B, vec3 A0, vec3 A1, vec3 A2, vec3 A3) { return !eq(B, A0) && !eq(B, A1) && !eq(B, A2) && !eq(B, A3); }\n"
	"void main() {\n"
	"  ivec2 ip = ivec2(gl_FragCoord.xy);\n"
	"  gBase = ip >> 1;\n"
	"  gMax = ivec2(texSize) - 1;\n"
	"  vec3 A = S(-1, -1), B = S(0, -1), C = S(1, -1);\n"
	"  vec3 D = S(-1, 0), E = S(0, 0), Fp = S(1, 0);\n"
	"  vec3 G = S(-1, 1), H = S(0, 1), I = S(1, 1);\n"
	"  vec3 P = S(0, -2), Sx = S(0, 2), Q = S(-2, 0), Rr = S(2, 0);\n"
	"  float Bl = luma(B), Dl = luma(D), El = luma(E), Fl = luma(Fp), Hl = luma(H);\n"
	"  vec3 J = E, K = E, L = E, M = E;\n"
	// 1:1 slope rules
	"  if ((eq(D, B) && !eq(D, H) && !eq(D, Fp)) && (El >= Dl || eq(E, A)) && any_eq3(E, A, C, G) && ((El < Dl) || !eq(A, D) || !eq(E, P) || !eq(E, Q))) J = D;\n"
	"  if ((eq(B, Fp) && !eq(B, D) && !eq(B, H)) && (El >= Bl || eq(E, C)) && any_eq3(E, A, C, I) && ((El < Bl) || !eq(C, B) || !eq(E, P) || !eq(E, Rr))) K = B;\n"
	"  if ((eq(H, D) && !eq(H, Fp) && !eq(H, B)) && (El >= Hl || eq(E, G)) && any_eq3(E, A, G, I) && ((El < Hl) || !eq(G, H) || !eq(E, Sx) || !eq(E, Q))) L = H;\n"
	"  if ((eq(Fp, H) && !eq(Fp, B) && !eq(Fp, D)) && (El >= Fl || eq(E, I)) && any_eq3(E, C, G, I) && ((El < Fl) || !eq(I, H) || !eq(E, Rr) || !eq(E, Sx))) M = Fp;\n"
	// intersection rules
	"  if ((!eq(E, Fp) && all_eq4(E, C, I, D, Q) && all_eq2(Fp, B, H)) && !eq(Fp, S(3, 0))) { K = Fp; M = Fp; }\n"
	"  if ((!eq(E, D) && all_eq4(E, A, G, Fp, Rr) && all_eq2(D, B, H)) && !eq(D, S(-3, 0))) { J = D; L = D; }\n"
	"  if ((!eq(E, H) && all_eq4(E, G, I, B, P) && all_eq2(H, D, Fp)) && !eq(H, S(0, 3))) { L = H; M = H; }\n"
	"  if ((!eq(E, B) && all_eq4(E, A, C, H, Sx) && all_eq2(B, D, Fp)) && !eq(B, S(0, -3))) { J = B; K = B; }\n"
	"  if (Bl < El && all_eq4(E, G, H, I, Sx) && none_eq4(E, A, D, C, Fp)) { J = B; K = B; }\n"
	"  if (Hl < El && all_eq4(E, A, B, C, P) && none_eq4(E, D, G, I, Fp)) { L = H; M = H; }\n"
	"  if (Fl < El && all_eq4(E, A, D, G, Q) && none_eq4(E, B, C, I, H)) { K = Fp; M = Fp; }\n"
	"  if (Dl < El && all_eq4(E, C, Fp, I, Rr) && none_eq4(E, B, A, G, H)) { J = D; L = D; }\n"
	// 2:1 slope rules
	"  if (!eq(H, B)) {\n"
	"    if (!eq(H, A) && !eq(H, E) && !eq(H, C)) {\n"
	"      if (all_eq3(H, G, Fp, Rr) && none_eq2(H, D, S(2, -1))) L = M;\n"
	"      if (all_eq3(H, I, D, Q) && none_eq2(H, Fp, S(-2, -1))) M = L;\n"
	"    }\n"
	"    if (!eq(B, I) && !eq(B, G) && !eq(B, E)) {\n"
	"      if (all_eq3(B, A, Fp, Rr) && none_eq2(B, D, S(2, 1))) J = K;\n"
	"      if (all_eq3(B, C, D, Q) && none_eq2(B, Fp, S(-2, 1))) K = J;\n"
	"    }\n"
	"  }\n"
	"  if (!eq(Fp, D)) {\n"
	"    if (!eq(D, I) && !eq(D, E) && !eq(D, C)) {\n"
	"      if (all_eq3(D, A, H, Sx) && none_eq2(D, B, S(1, 2))) J = L;\n"
	"      if (all_eq3(D, G, B, P) && none_eq2(D, H, S(1, -2))) L = J;\n"
	"    }\n"
	"    if (!eq(Fp, E) && !eq(Fp, A) && !eq(Fp, G)) {\n"
	"      if (all_eq3(Fp, C, H, Sx) && none_eq2(Fp, B, S(-1, 2))) K = M;\n"
	"      if (all_eq3(Fp, I, B, P) && none_eq2(Fp, H, S(-1, -2))) M = K;\n"
	"    }\n"
	"  }\n"
	"  ivec2 sub = ip & 1;\n"
	"  vec3 outc = sub.y == 0 ? (sub.x == 0 ? J : K) : (sub.x == 0 ? L : M);\n"
	"  color = vec4(outc, 1.0);\n"
	"}\n";

static bool _prog(Prog* p, const char* name, const char* fragSrc);
static void _use(const Prog* p, bool flipY);
static void _bind(int unit, GLuint tex);
static void _quad(void);
static void _drawBlit(GLuint tex, int tw, int th, int outW, int outH, int mode, bool flip);

// Ambient layer: samples the mip-mapped, downscaled frame at a high LOD with a 3x3 tap spread (smooths the
// box-filter blockiness), saturation x1.4, brightness 42% (labels on it sit on glass chips). cover = uv scale that makes the frame cover the view.
static const char* FRAG_AMB =
	"#version 300 es\n"
	"precision highp float;\n"
	"in vec2 uv;\n"
	"out vec4 color;\n"
	"uniform sampler2D tex;\n"
	"uniform vec2 outSize;\n"
	"void main() {\n"
	"  vec2 c = (uv - 0.5) * outSize + 0.5;\n"
	"  vec3 sum = vec3(0.0);\n"
	"  for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {\n"
	"    sum += textureLod(tex, c + vec2(float(x), float(y)) * 0.03, 3.0).rgb;\n"
	"  }\n"
	"  vec3 rgb = sum / 9.0;\n"
	"  float l = dot(rgb, vec3(0.299, 0.587, 0.114));\n"
	"  rgb = clamp(mix(vec3(l), rgb, 1.4), 0.0, 1.0) * 0.42;\n"
	"  color = vec4(rgb, 1.0);\n"
	"}\n";

// Picture rect (surface pixels, origin top-left) and ambient switch; set from any thread.
static volatile int g_rectL, g_rectT, g_rectW, g_rectH;
static volatile int g_ambient = 1;

void renderSetPictureRect(int l, int t, int w, int h) {
	g_rectL = l;
	g_rectT = t;
	g_rectW = w;
	g_rectH = h;
}

void renderSetAmbient(bool on) {
	g_ambient = on ? 1 : 0;
}

#define AMB_W 120
#define AMB_H 80

static bool _ambTarget(void) {
	if (R.ambTex) {
		return true;
	}
	glGenTextures(1, &R.ambTex);
	_bind(0, R.ambTex);
	glTexStorage2D(GL_TEXTURE_2D, 7, GL_RGBA8, AMB_W, AMB_H);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	glGenFramebuffers(1, &R.ambFbo);
	glBindFramebuffer(GL_FRAMEBUFFER, R.ambFbo);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, R.ambTex, 0);
	GLenum st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
	if (st != GL_FRAMEBUFFER_COMPLETE) {
		glDeleteFramebuffers(1, &R.ambFbo);
		glDeleteTextures(1, &R.ambTex);
		R.ambFbo = R.ambTex = 0;
		R.amb.tried = true; // give up
		return false;
	}
	R.ambMips = false;
	return true;
}

// Draws the ambient layer over the whole surface; refreshes the small mip-mapped copy only when news.
static void _drawAmbient(bool isNew, int fw, int fh) {
	if (!g_ambient || !_prog(&R.amb, "ambient", FRAG_AMB) || !_ambTarget()) {
		return;
	}
	if (isNew || !R.ambMips) {
		glBindFramebuffer(GL_FRAMEBUFFER, R.ambFbo);
		glViewport(0, 0, AMB_W, AMB_H);
		_drawBlit(R.frameTex, fw, fh, AMB_W, AMB_H, F_SMOOTH, false);
		_bind(0, R.ambTex);
		glGenerateMipmap(GL_TEXTURE_2D);
		R.ambMips = true;
	}
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
	glViewport(0, 0, R.viewW, R.viewH);
	float va = (float) R.viewW / (float) (R.viewH > 0 ? R.viewH : 1), fa = (float) fw / fh;
	float sx = 1.0f, sy = 1.0f;
	if (va > fa) {
		sy = fa / va;
	} else {
		sx = va / fa;
	}
	_use(&R.amb, true);
	_bind(0, R.ambTex);
	glUniform2f(R.amb.uOutSize, sx, sy);
	_quad();
}

// -------------------------------------------------------------------------------------------- helpers

static GLuint _compile(GLenum type, const char* name, const char* src) {
	GLuint s = glCreateShader(type);
	glShaderSource(s, 1, &src, NULL);
	glCompileShader(s);
	GLint ok = 0;
	glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
	if (!ok) {
		char log[1024];
		glGetShaderInfoLog(s, sizeof(log), NULL, log);
		LOGE("shader %s: compile failed: %s", name, log);
		glDeleteShader(s);
		return 0;
	}
	return s;
}

// Builds a program from the shared vertex shader and the given fragment source. On any error it logs the
// driver's message and leaves p->id == 0.
static bool _build(Prog* p, const char* name, const char* fragSrc) {
	p->tried = true;
	p->id = 0;
	GLuint fs = _compile(GL_FRAGMENT_SHADER, name, fragSrc);
	if (!fs) {
		return false;
	}
	GLuint prog = glCreateProgram();
	glAttachShader(prog, R.vs);
	glAttachShader(prog, fs);
	glLinkProgram(prog);
	glDeleteShader(fs);
	GLint ok = 0;
	glGetProgramiv(prog, GL_LINK_STATUS, &ok);
	if (!ok) {
		char log[1024];
		glGetProgramInfoLog(prog, sizeof(log), NULL, log);
		LOGE("shader %s: link failed: %s", name, log);
		glDeleteProgram(prog);
		return false;
	}
	p->id = prog;
	p->uFlip = glGetUniformLocation(prog, "flipY");
	p->uTexSize = glGetUniformLocation(prog, "texSize");
	p->uOutSize = glGetUniformLocation(prog, "outSize");
	p->uMode = glGetUniformLocation(prog, "filterMode");
	p->uSharp = glGetUniformLocation(prog, "sharp");
	p->uInSize = glGetUniformLocation(prog, "inSize");
	glUseProgram(prog);
	GLint tex = glGetUniformLocation(prog, "tex");
	if (tex >= 0) {
		glUniform1i(tex, 0);
	}
	return true;
}

static bool _prog(Prog* p, const char* name, const char* fragSrc) {
	if (!p->tried) {
		_build(p, name, fragSrc);
	}
	return p->id != 0;
}

static void _use(const Prog* p, bool flipY) {
	glUseProgram(p->id);
	glUniform1f(p->uFlip, flipY ? 1.0f : 0.0f);
}

static void _bind(int unit, GLuint tex) {
	glActiveTexture(GL_TEXTURE0 + unit);
	glBindTexture(GL_TEXTURE_2D, tex);
}

static void _texParams(GLenum filter) {
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
}

static void _freeTarget(Target* t) {
	if (t->fbo) {
		glDeleteFramebuffers(1, &t->fbo);
	}
	if (t->tex) {
		glDeleteTextures(1, &t->tex);
	}
	memset(t, 0, sizeof(*t));
}

// Makes sure t is a complete w x h render target (recreated on size change; marks the renderer dirty).
static bool _target(Target* t, int w, int h, GLenum ifmt, GLenum filter) {
	if (t->tex && t->w == w && t->h == h) {
		return true;
	}
	_freeTarget(t);
	glGenTextures(1, &t->tex);
	_bind(0, t->tex);
	glTexStorage2D(GL_TEXTURE_2D, 1, ifmt, w, h);
	_texParams(filter);
	glGenFramebuffers(1, &t->fbo);
	glBindFramebuffer(GL_FRAMEBUFFER, t->fbo);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t->tex, 0);
	GLenum st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
	if (st != GL_FRAMEBUFFER_COMPLETE) {
		LOGE("render target %dx%d fmt 0x%x incomplete (0x%x)", w, h, ifmt, st);
		_freeTarget(t);
		return false;
	}
	t->w = w;
	t->h = h;
	R.dirty = true;
	return true;
}

static void _beginTarget(const Target* t) {
	glBindFramebuffer(GL_FRAMEBUFFER, t->fbo);
	glViewport(0, 0, t->w, t->h);
	const GLenum att = GL_COLOR_ATTACHMENT0;
	glInvalidateFramebuffer(GL_FRAMEBUFFER, 1, &att); // full overdraw follows: no need to load old contents
}

static void _quad(void) {
	glBindVertexArray(R.vao);
	glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

// Draws tex through a "filterMode" program into the currently bound framebuffer / viewport.
static void _drawBlit(GLuint tex, int tw, int th, int outW, int outH, int mode, bool flip) {
	_use(&R.blit, flip);
	_bind(0, tex);
	glUniform2f(R.blit.uTexSize, (float) tw, (float) th);
	glUniform2f(R.blit.uOutSize, (float) outW, (float) outH);
	glUniform1i(R.blit.uMode, mode);
	_quad();
}

static void _drawEasu(GLuint tex, int tw, int th, bool flip) {
	_use(&R.easu, flip);
	_bind(0, tex);
	glUniform2f(R.easu.uTexSize, (float) tw, (float) th);
	_quad();
}

// ----------------------------------------------------------------------------------- Anime4K CNN passes

// Wraps one mpv-hook pass body (anime4k.h) into a GLES 3.0 fragment shader. The mpv texture macros are
// re-implemented: <name>_pos / _size / _pt / _tex(p) / _texOff(offset in texels). Conv passes render 1:1 into
// a texture of the input size, so their neighbour reads are plain texelFetch at gl_FragCoord + offset.
static char* _a4kSource(const A4KPass* p) {
	size_t cap = strlen(p->src) + 8192;
	char* s = (char*) malloc(cap);
	if (!s) {
		return NULL;
	}
	size_t n = 0;
	n += snprintf(s + n, cap - n,
		"#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\n"
		"in vec2 uv;\nout vec4 fragColor;\nuniform vec2 inSize;\n");
	for (int i = 0; i < p->nIn; i++) {
		const char* nm = p->inName[i];
		n += snprintf(s + n, cap - n, "uniform sampler2D T%d;\n", i);
		n += snprintf(s + n, cap - n, "#define %s_pos uv\n#define %s_size inSize\n#define %s_pt (vec2(1.0) / inSize)\n", nm, nm, nm);
		n += snprintf(s + n, cap - n, "#define %s_tex(p) texture(T%d, (p))\n", nm, i);
		if (p->outIdx != 0) {
			n += snprintf(s + n, cap - n,
				"#define %s_texOff(o) texelFetch(T%d, clamp(ivec2(gl_FragCoord.xy) + ivec2(o), ivec2(0), ivec2(inSize) - 1), 0)\n", nm, i);
		} else {
			n += snprintf(s + n, cap - n, "#define %s_texOff(o) texture(T%d, uv + (o) / inSize)\n", nm, i);
		}
	}
	n += snprintf(s + n, cap - n, "%s\nvoid main() { fragColor = hook(); }\n", p->src);
	return s;
}

// Compiles the network's passes the first time AI is used. False (and R.bad) when the GPU cannot do it.
static bool _aiBuild(void) {
	if (R.aiTried) {
		return R.ai[0].id != 0;
	}
	R.aiTried = true;
	const A4KNet* net = &A4K_NETS[AI_NET];
	for (int i = 0; i < net->count; i++) {
		const A4KPass* p = &net->passes[i];
		char* src = _a4kSource(p);
		char name[32];
		snprintf(name, sizeof(name), "anime4k pass %d", i);
		bool ok = src && _build(&R.ai[i], name, src);
		free(src);
		if (!ok) {
			for (int k = 0; k <= i; k++) {
				if (R.ai[k].id) {
					glDeleteProgram(R.ai[k].id);
				}
				memset(&R.ai[k], 0, sizeof(R.ai[k]));
			}
			return false;
		}
		for (int k = 0; k < p->nIn; k++) {
			char u[8];
			snprintf(u, sizeof(u), "T%d", k);
			glUniform1i(glGetUniformLocation(R.ai[i].id, u), k);
		}
	}
	return true;
}

// Allocates the textures of both x2 stages for a w x h frame. RGBA16F render targets need
// EXT_color_buffer_half_float / _float; if the FBO is incomplete this fails and AI falls back to FSR.
static bool _aiTargets(int w, int h) {
	const A4KNet* net = &A4K_NETS[AI_NET];
	int iw = w, ih = h;
	for (int s = 0; s < AI_STAGES; s++) {
		A4KStage* st = &R.stage[s];
		for (int i = 1; i < net->slots; i++) {
			if (!_target(&st->slot[i], iw, ih, GL_RGBA16F, GL_NEAREST)) {
				return false;
			}
		}
		if (!_target(&st->out, iw * 2, ih * 2, GL_RGBA8, GL_LINEAR)) {
			return false;
		}
		iw *= 2;
		ih *= 2;
	}
	return true;
}

// Runs the whole network once: inTex (iw x ih) -> st->out (2*iw x 2*ih).
static void _aiStage(A4KStage* st, GLuint inTex, int iw, int ih) {
	const A4KNet* net = &A4K_NETS[AI_NET];
	for (int i = 0; i < net->count; i++) {
		const A4KPass* p = &net->passes[i];
		const Prog* pr = &R.ai[i];
		const Target* dst = p->outIdx == 0 ? &st->out : &st->slot[p->outIdx];
		_beginTarget(dst);
		_use(pr, false);
		glUniform2f(pr->uInSize, (float) iw, (float) ih);
		for (int k = 0; k < p->nIn; k++) {
			int idx = p->inIdx[k];
			_bind(k, idx == 0 ? inTex : st->slot[idx].tex);
		}
		_quad();
	}
}

// ------------------------------------------------------------------------------------------- lifecycle

void renderInit(void) {
	// A new GL context: every old object id is dead with it. Forget them all.
	memset(&R, 0, sizeof(R));
	R.dirty = true;
	R.lastFilter = -1;

	GLuint vs = _compile(GL_VERTEX_SHADER, "vertex", VERT);
	R.vs = vs;
	_build(&R.blit, "blit", FRAG_BLIT);
	glGenVertexArrays(1, &R.vao);
	glGenTextures(1, &R.frameTex);
	R.frameW = 240;
	R.frameH = 160;
	_bind(0, R.frameTex);
	glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, R.frameW, R.frameH, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
	_texParams(GL_LINEAR);
	glDisable(GL_BLEND);
	glDisable(GL_DEPTH_TEST);
	LOGI("renderer: %s / %s", (const char*) glGetString(GL_RENDERER), (const char*) glGetString(GL_VERSION));
	bridgeFrameForceNew();
}

void renderResize(int w, int h) {
	if (w != R.viewW || h != R.viewH) {
		R.dirty = true;
	}
	R.viewW = w;
	R.viewH = h;
}

// Falls back FSR <- AI, Sharp <- MMPX / FSR when a filter is not available on this GPU.
static int _fallback(int f) {
	if (f < 0 || f >= F_COUNT) {
		f = F_SHARP;
	}
	while (R.bad[f]) {
		f = (f == F_AI) ? F_FSR : F_SHARP;
	}
	return f;
}

// Builds programs and offscreen targets that filter f needs. False = filter unusable (caller falls back).
static bool _prepare(int f, int fw, int fh, int w, int h) {
	switch (f) {
	case F_FSR:
		return _prog(&R.easu, "fsr easu", FRAG_EASU) && _prog(&R.rcas, "fsr rcas", FRAG_RCAS)
			&& _target(&R.fsr, w, h, GL_RGBA8, GL_NEAREST);
	case F_MMPX:
		return _prog(&R.mmpx, "mmpx", FRAG_MMPX) && _target(&R.mmpx2x, fw * 2, fh * 2, GL_RGBA8, GL_LINEAR);
	case F_AI:
		return _prog(&R.easu, "fsr easu", FRAG_EASU) && _aiBuild() && _aiTargets(fw, fh);
	default:
		return true;
	}
}

void renderDraw(int filter) {
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
	glViewport(0, 0, R.viewW, R.viewH);
	glClearColor(0, 0, 0, 1);
	glClear(GL_COLOR_BUFFER_BIT);
	const uint32_t* px;
	int fw, fh;
	bool isNew;
	if (!bridgeFrameAcquire(&px, &fw, &fh, &isNew)) {
		return;
	}
	if (fw != R.frameW || fh != R.frameH) {
		R.frameW = fw;
		R.frameH = fh;
		_bind(0, R.frameTex);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, fw, fh, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
		isNew = true;
		R.dirty = true;
	}
	_bind(0, R.frameTex);
	if (isNew) {
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, fw, fh, GL_RGBA, GL_UNSIGNED_BYTE, px);
	}
	bridgeFrameRelease(isNew);

	float scale = (float) R.viewW / fw;
	if ((float) R.viewH / fh < scale) {
		scale = (float) R.viewH / fh;
	}
	int w = (int) (fw * scale), h = (int) (fh * scale);
	int px0 = (R.viewW - w) / 2, py0 = (R.viewH - h) / 2;
	if (g_rectW > 0 && g_rectH > 0) {
		w = g_rectW;
		h = g_rectH;
		px0 = g_rectL;
		py0 = R.viewH - g_rectT - g_rectH; // GL origin is bottom-left
	}
	if (w < 1 || h < 1 || !R.blit.id) {
		return;
	}

	int f = _fallback(filter);
	while (!_prepare(f, fw, fh, w, h)) {
		LOGE("filter %d unavailable on this GPU, falling back", f);
		R.bad[f] = true;
		f = _fallback(f);
	}
	// Expensive stages only when something they depend on changed.
	bool heavy = isNew || R.dirty || f != R.lastFilter || w != R.lastW || h != R.lastH;
	R.dirty = false;
	R.lastFilter = f;
	R.lastW = w;
	R.lastH = h;

	GLuint finalTex = R.frameTex;
	int finalW = fw, finalH = fh;
	int mode = F_SMOOTH;
	switch (f) {
	case F_PIXEL:
	case F_SHARP:
	case F_SMOOTH:
		mode = f;
		break;
	case F_FSR:
		if (heavy) {
			_beginTarget(&R.fsr);
			_drawEasu(R.frameTex, fw, fh, false);
		}
		break;
	case F_MMPX:
		if (heavy) {
			_beginTarget(&R.mmpx2x);
			_use(&R.mmpx, false);
			_bind(0, R.frameTex);
			glUniform2f(R.mmpx.uTexSize, (float) fw, (float) fh);
			_quad();
		}
		finalTex = R.mmpx2x.tex;
		finalW = R.mmpx2x.w;
		finalH = R.mmpx2x.h;
		mode = F_SHARP;
		break;
	case F_AI:
		if (heavy) {
			_aiStage(&R.stage[0], R.frameTex, fw, fh);
			_aiStage(&R.stage[1], R.stage[0].out.tex, fw * 2, fh * 2);
		}
		finalTex = R.stage[AI_STAGES - 1].out.tex;
		finalW = R.stage[AI_STAGES - 1].out.w;
		finalH = R.stage[AI_STAGES - 1].out.h;
		break;
	}

	// Last pass: the letterboxed picture into the window.
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
	_drawAmbient(isNew, fw, fh);
	glViewport(px0, py0, w, h);
	if (f == F_FSR) {
		_use(&R.rcas, true);
		_bind(0, R.fsr.tex);
		glUniform2f(R.rcas.uTexSize, (float) R.fsr.w, (float) R.fsr.h);
		glUniform1f(R.rcas.uSharp, 0.7071f); // exp2(-0.5): sharpness 0.5 stops, softer than AMD's maximum
		_quad();
	} else if (f == F_AI && w > finalW) {
		_drawEasu(finalTex, finalW, finalH, true); // still upscaling (e.g. portrait on a big panel)
	} else {
		_drawBlit(finalTex, finalW, finalH, w, h, f == F_AI ? F_SMOOTH : mode, true);
	}
}

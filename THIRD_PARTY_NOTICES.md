# Third-party notices

## mGBA

- Source: https://github.com/mgba-emu/mgba (git submodule at `vendor/mgba`, unmodified)
- Copyright (c) 2013-2026 Jeffrey Pfau
- License: Mozilla Public License 2.0, https://mozilla.org/MPL/2.0/

The app links a minimal static build of libmgba (GBA core only). Its complete source is the commit
that `vendor/mgba` points to.

## Filters in `android/app/src/main/cpp/render.c` and `anime4k.h`

Each is re-implemented in GLSL ES 3.00, or embedded, under the MIT License below.

- **FidelityFX Super Resolution 1.0 (EASU, RCAS)**: Copyright (c) 2021 Advanced Micro Devices, Inc.
  https://github.com/GPUOpen-Effects/FidelityFX-FSR
- **MMPX Style-Preserving Pixel Art Magnification**: Copyright (c) 2021 Morgan McGuire and Mara Gagiu.
  https://casual-effects.com/research/McGuire2021PixelArt/
- **Anime4K v3.2 Upscale CNN x2** (network weights and passes): Copyright (c) 2019-2021 bloc97.
  https://github.com/bloc97/Anime4K

### MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial
portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

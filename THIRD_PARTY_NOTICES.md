# Third-party notices

Link Play's own code is MIT licensed, see [LICENSE](LICENSE). What else is in each app:

- Android app: mGBA, inih (part of mGBA), the Kotlin standard library, and the filter code listed under "Filters" below.
- macOS and Windows app: mGBA, inih (part of mGBA), the Kotlin standard library and a Java runtime. The Windows app also links parts of the MinGW-w64 runtime.

## mGBA (every app)

- Source: https://github.com/mgba-emu/mgba (git submodule at `vendor/mgba`, unmodified)
- Copyright (c) 2013-2026 Jeffrey Pfau
- License: Mozilla Public License 2.0, https://mozilla.org/MPL/2.0/

Every app links a minimal static build of libmgba (GBA core only): into the Android app's native library,
and into `libgbalink.dylib` (macOS) or `gbalink.dll` (Windows). The complete source is the commit that
`vendor/mgba` points to. The macOS and Windows apps carry the licence text as `LICENSE-mGBA.txt` next to
this file.

## inih (every app, part of libmgba)

- Source: https://github.com/benhoyt/inih (the copy inside mGBA, `vendor/mgba/src/third-party/inih`)
- Copyright (c) 2009, Ben Hoyt
- License: BSD-3-Clause ("New BSD"), full text below.

mGBA compiles this INI file parser into libmgba, so it is in every app. The macOS and Windows apps carry
the licence text as `LICENSE-inih.txt` next to this file.

```text
The "inih" library is distributed under the New BSD license:

Copyright (c) 2009, Ben Hoyt
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.
    * Neither the name of Ben Hoyt nor the names of its contributors
      may be used to endorse or promote products derived from this software
      without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY BEN HOYT ''AS IS'' AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL BEN HOYT BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## Filters in `android/app/src/main/cpp/render.c` and `anime4k.h` (Android app only)

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

## Java runtime (macOS and Windows app)

- OpenJDK 17, https://openjdk.org. The release builds use the Eclipse Temurin 17 build, https://adoptium.net.
- License: GNU General Public License version 2 with the Classpath Exception,
  https://openjdk.org/legal/gplv2+ce.html
- Source: https://github.com/openjdk/jdk17u (Temurin: https://github.com/adoptium/jdk17u). Link Play does not
  change the runtime. It is cut down with `jlink` to the modules the app needs (`java.base`, `java.desktop`,
  `java.logging`, `java.prefs` and the modules they depend on).
- `jlink` copies the legal notices of every included module into the runtime, in the `legal` folder:
  `Link Play.app/Contents/runtime/Contents/Home/legal` on macOS, `runtime\legal` in the Windows install
  folder or the unzipped portable folder. Each module folder has the full licence texts (`LICENSE`,
  `ASSEMBLY_EXCEPTION`) and the notices for third-party code inside the runtime.

## MinGW-w64 runtime (Windows app only)

`gbalink.dll` is built with MinGW-w64 GCC and linked with `-static -static-libgcc`, so these parts of the
compiler's runtime are inside the DLL itself (there are no separate runtime DLLs). The macOS and Android
apps do not contain them.

- **winpthreads**: MIT and BSD-style licences, part of the mingw-w64 project. https://www.mingw-w64.org,
  licence file: https://github.com/mingw-w64/mingw-w64/tree/master/mingw-w64-libraries/winpthreads
  (`COPYING`).
- **mingw-w64 CRT** (C runtime start-up code and import libraries): ZPL, BSD and public-domain parts.
  https://www.mingw-w64.org, licence files: `COPYING` and `COPYING.MinGW-w64-runtime` at the top of
  https://github.com/mingw-w64/mingw-w64.
- **libgcc**: GNU General Public License version 3 with the GCC Runtime Library Exception,
  https://www.gnu.org/licenses/gcc-exception-3.1.html. Source: https://gcc.gnu.org.

The licence texts are those shipped in MSYS2's `/ucrt64/share/licenses`; the list is to be confirmed against
the first Windows CI build.

## Kotlin standard library (every app)

- The Kotlin standard library: `kotlin-stdlib-<version>.jar` in the macOS and Windows app folder, compiled
  into the APK on Android. Copyright (c) JetBrains s.r.o. and Kotlin Programming Language contributors.
  https://kotlinlang.org
- JetBrains annotations (`annotations-13.0.jar`, a small companion of the standard library):
  Copyright (c) JetBrains s.r.o. https://github.com/JetBrains/java-annotations
- License for both: Apache License 2.0, https://www.apache.org/licenses/LICENSE-2.0 (full text below).

### Apache License 2.0

```text
                              Apache License
                        Version 2.0, January 2004
                     http://www.apache.org/licenses/

TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

1. Definitions.

   "License" shall mean the terms and conditions for use, reproduction,
   and distribution as defined by Sections 1 through 9 of this document.

   "Licensor" shall mean the copyright owner or entity authorized by
   the copyright owner that is granting the License.

   "Legal Entity" shall mean the union of the acting entity and all
   other entities that control, are controlled by, or are under common
   control with that entity. For the purposes of this definition,
   "control" means (i) the power, direct or indirect, to cause the
   direction or management of such entity, whether by contract or
   otherwise, or (ii) ownership of fifty percent (50%) or more of the
   outstanding shares, or (iii) beneficial ownership of such entity.

   "You" (or "Your") shall mean an individual or Legal Entity
   exercising permissions granted by this License.

   "Source" form shall mean the preferred form for making modifications,
   including but not limited to software source code, documentation
   source, and configuration files.

   "Object" form shall mean any form resulting from mechanical
   transformation or translation of a Source form, including but
   not limited to compiled object code, generated documentation,
   and conversions to other media types.

   "Work" shall mean the work of authorship, whether in Source or
   Object form, made available under the License, as indicated by a
   copyright notice that is included in or attached to the work
   (an example is provided in the Appendix below).

   "Derivative Works" shall mean any work, whether in Source or Object
   form, that is based on (or derived from) the Work and for which the
   editorial revisions, annotations, elaborations, or other modifications
   represent, as a whole, an original work of authorship. For the purposes
   of this License, Derivative Works shall not include works that remain
   separable from, or merely link (or bind by name) to the interfaces of,
   the Work and Derivative Works thereof.

   "Contribution" shall mean any work of authorship, including
   the original version of the Work and any modifications or additions
   to that Work or Derivative Works thereof, that is intentionally
   submitted to Licensor for inclusion in the Work by the copyright owner
   or by an individual or Legal Entity authorized to submit on behalf of
   the copyright owner. For the purposes of this definition, "submitted"
   means any form of electronic, verbal, or written communication sent
   to the Licensor or its representatives, including but not limited to
   communication on electronic mailing lists, source code control systems,
   and issue tracking systems that are managed by, or on behalf of, the
   Licensor for the purpose of discussing and improving the Work, but
   excluding communication that is conspicuously marked or otherwise
   designated in writing by the copyright owner as "Not a Contribution."

   "Contributor" shall mean Licensor and any individual or Legal Entity
   on behalf of whom a Contribution has been received by Licensor and
   subsequently incorporated within the Work.

2. Grant of Copyright License. Subject to the terms and conditions of
   this License, each Contributor hereby grants to You a perpetual,
   worldwide, non-exclusive, no-charge, royalty-free, irrevocable
   copyright license to reproduce, prepare Derivative Works of,
   publicly display, publicly perform, sublicense, and distribute the
   Work and such Derivative Works in Source or Object form.

3. Grant of Patent License. Subject to the terms and conditions of
   this License, each Contributor hereby grants to You a perpetual,
   worldwide, non-exclusive, no-charge, royalty-free, irrevocable
   (except as stated in this section) patent license to make, have made,
   use, offer to sell, sell, import, and otherwise transfer the Work,
   where such license applies only to those patent claims licensable
   by such Contributor that are necessarily infringed by their
   Contribution(s) alone or by combination of their Contribution(s)
   with the Work to which such Contribution(s) was submitted. If You
   institute patent litigation against any entity (including a
   cross-claim or counterclaim in a lawsuit) alleging that the Work
   or a Contribution incorporated within the Work constitutes direct
   or contributory patent infringement, then any patent licenses
   granted to You under this License for that Work shall terminate
   as of the date such litigation is filed.

4. Redistribution. You may reproduce and distribute copies of the
   Work or Derivative Works thereof in any medium, with or without
   modifications, and in Source or Object form, provided that You
   meet the following conditions:

   (a) You must give any other recipients of the Work or
       Derivative Works a copy of this License; and

   (b) You must cause any modified files to carry prominent notices
       stating that You changed the files; and

   (c) You must retain, in the Source form of any Derivative Works
       that You distribute, all copyright, patent, trademark, and
       attribution notices from the Source form of the Work,
       excluding those notices that do not pertain to any part of
       the Derivative Works; and

   (d) If the Work includes a "NOTICE" text file as part of its
       distribution, then any Derivative Works that You distribute must
       include a readable copy of the attribution notices contained
       within such NOTICE file, excluding those notices that do not
       pertain to any part of the Derivative Works, in at least one
       of the following places: within a NOTICE text file distributed
       as part of the Derivative Works; within the Source form or
       documentation, if provided along with the Derivative Works; or,
       within a display generated by the Derivative Works, if and
       wherever such third-party notices normally appear. The contents
       of the NOTICE file are for informational purposes only and
       do not modify the License. You may add Your own attribution
       notices within Derivative Works that You distribute, alongside
       or as an addendum to the NOTICE text from the Work, provided
       that such additional attribution notices cannot be construed
       as modifying the License.

   You may add Your own copyright statement to Your modifications and
   may provide additional or different license terms and conditions
   for use, reproduction, or distribution of Your modifications, or
   for any such Derivative Works as a whole, provided Your use,
   reproduction, and distribution of the Work otherwise complies with
   the conditions stated in this License.

5. Submission of Contributions. Unless You explicitly state otherwise,
   any Contribution intentionally submitted for inclusion in the Work
   by You to the Licensor shall be under the terms and conditions of
   this License, without any additional terms or conditions.
   Notwithstanding the above, nothing herein shall supersede or modify
   the terms of any separate license agreement you may have executed
   with Licensor regarding such Contributions.

6. Trademarks. This License does not grant permission to use the trade
   names, trademarks, service marks, or product names of the Licensor,
   except as required for reasonable and customary use in describing the
   origin of the Work and reproducing the content of the NOTICE file.

7. Disclaimer of Warranty. Unless required by applicable law or
   agreed to in writing, Licensor provides the Work (and each
   Contributor provides its Contributions) on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
   implied, including, without limitation, any warranties or conditions
   of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
   PARTICULAR PURPOSE. You are solely responsible for determining the
   appropriateness of using or redistributing the Work and assume any
   risks associated with Your exercise of permissions under this License.

8. Limitation of Liability. In no event and under no legal theory,
   whether in tort (including negligence), contract, or otherwise,
   unless required by applicable law (such as deliberate and grossly
   negligent acts) or agreed to in writing, shall any Contributor be
   liable to You for damages, including any direct, indirect, special,
   incidental, or consequential damages of any character arising as a
   result of this License or out of the use or inability to use the
   Work (including but not limited to damages for loss of goodwill,
   work stoppage, computer failure or malfunction, or any and all
   other commercial damages or losses), even if such Contributor
   has been advised of the possibility of such damages.

9. Accepting Warranty or Additional Liability. While redistributing
   the Work or Derivative Works thereof, You may choose to offer,
   and charge a fee for, acceptance of support, warranty, indemnity,
   or other liability obligations and/or rights consistent with this
   License. However, in accepting such obligations, You may act only
   on Your own behalf and on Your sole responsibility, not on behalf
   of any other Contributor, and only if You agree to indemnify,
   defend, and hold each Contributor harmless for any liability
   incurred by, or claims asserted against, such Contributor by reason
   of your accepting any such warranty or additional liability.

END OF TERMS AND CONDITIONS
```

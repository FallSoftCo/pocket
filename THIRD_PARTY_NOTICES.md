# Third-party components

Pocket's original code is MIT licensed. Dependencies retain their own licenses.
The npm lockfile and Android Gradle declarations identify the exact components.

- AndroidX (including WorkManager), Jetpack Compose, Gradle wrapper, Kotlin, OkHttp and Coil: Apache 2.0.
- Express, ws, Zod and the Model Context Protocol TypeScript SDK: MIT.
- Firebase Android SDK, Firebase Admin SDK and Google Auth Library: Apache 2.0.
- System fonts and platform services are supplied by Android.

Pocket is an independent companion. Codex is an OpenAI product and is not
bundled with this source package or APK. Authentication remains in the user's
existing Codex installation. Tailscale is independently installed by the user.

The Node lockfile overrides transitive `uuid` to 11.1.1 to address GHSA-w5hq-g745-h8pq in an older Google dependency. The consumer uses the compatible CommonJS `v4()` API; this was checked alongside the backend tests. Re-evaluate the override when updating Firebase dependencies.

## CommonMark Java 0.27.1

Markdown parsing uses [CommonMark Java](https://github.com/commonmark/commonmark-java), distributed under its BSD 2-clause license:

Copyright (c) 2015, Robin Stocker
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

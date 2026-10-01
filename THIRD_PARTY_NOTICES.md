# Third-party components

Pocodex's original code is MIT licensed. Dependencies retain their own licenses.
The npm lockfile and Android Gradle declarations identify the exact components.

- AndroidX (including WorkManager), Jetpack Compose, Gradle wrapper, Kotlin, OkHttp and Coil: Apache 2.0.
- Express, ws, Zod and the Model Context Protocol TypeScript SDK: MIT.
- Firebase Android SDK, Firebase Admin SDK and Google Auth Library: Apache 2.0.
- System fonts and platform services are supplied by Android.

Pocodex is an independent companion. Codex is an OpenAI product and is not
bundled with this source package or APK. Authentication remains in the user's
existing Codex installation. Tailscale is independently installed by the user.

The Node lockfile overrides transitive `uuid` to 11.1.1 to address GHSA-w5hq-g745-h8pq in an older Google dependency. The consumer uses the compatible CommonJS `v4()` API; this was checked alongside the backend tests. Re-evaluate the override when updating Firebase dependencies.

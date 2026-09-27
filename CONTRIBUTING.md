# Contributing

Use Node 22.13+ and run `npm ci && npm test`. Android needs JDK 17+, SDK 36, and `cd android && ./gradlew assembleDebug lintRelease`. CI also builds the unsigned release variant.

Open focused issues or pull requests with the behavior, reproduction, tested Codex/Android versions, and relevant validation. Use synthetic tasks and redact hostnames, project paths, and private content. Never commit data directories, APK signing keys, Firebase admin keys, or pairing tokens. Report security issues using the private process in SECURITY.md.

Maintain stock Codex compatibility, exact thread/request routing, inherited permissions, durable delivery states, and the distinction between accepted, delivered, and read. Do not silently retry ambiguous task creation or replies. Tests should exercise behavior and trust boundaries. New protocol support needs a documented tested Codex version.

Contributions are under the repository's MIT license.

Read the [contribution policy](MAINTAINER_POLICY.md) for project direction and automated review. Pocket Maintainer can request corrections, approve and merge suitable changes after checks, or explain why a direction belongs in a fork. Code changes require two agreeing reviews. Uncertain decisions stay open with an explanation. Maintainers can override its decisions or apply `maintainer:hold` to pause a PR.

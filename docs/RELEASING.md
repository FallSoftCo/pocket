# Android releases and upgrades

## Public identity

Public APKs use package `co.fallsoft.pocket`, a stable private FallSoft signing key, and monotonically increasing version codes. The release includes `SHA256SUMS.txt` and `SIGNING-CERTIFICATE.txt`. Verify both the file checksum and signer before installing/updating:

```bash
sha256sum -c SHA256SUMS.txt
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --verbose --print-certs pocket-0.4.4-alpha.1.apk
```

The initial signing certificate SHA-256 fingerprint is:

```text
b28f55b44c9300b10db340e0c7cabfb90c0980713da9bac82324dfbf6013ccbf
```
 Keep the same certificate for future updates. Checksums prove artifact consistency; the certificate is the maintained Android update identity.

## Signed build

Use JDK 17+, Android SDK/platform/build-tools 36, and an absolute `ANDROID_HOME`. Generate your own private key **outside** the checkout if building an independently signed distribution. Never use a public or default debug key for a maintained release.

```bash
keytool -genkeypair -keystore /private/path/pocket-release.jks -alias pocket -keyalg RSA -keysize 4096 -validity 10000
export ANDROID_HOME=/absolute/path/Android/Sdk
export POCKET_SIGNING_STORE=/private/path/pocket-release.jks
export POCKET_SIGNING_ALIAS=pocket
read -rs POCKET_SIGNING_PASSWORD
export POCKET_SIGNING_PASSWORD
node scripts/build-release.mjs
unset POCKET_SIGNING_PASSWORD
```

For distinct key/store passwords, set `POCKET_SIGNING_KEY_PASSWORD` too. Do not put passwords in shell history, git, Gradle properties, logs, or release assets. Keep a secure off-machine backup of the signing key and credentials; losing them prevents updates under that identity. The signing script requires explicit credentials and has no debug fallback.

Unsigned CI builds validate compilation and lint; they are not installable releases and are never uploaded as release APKs. Original release builds are made locally with the private signing identity. Pin package/Android/backend versions together and increase `versionCode` before each release.

## Upgrade and migration

A later official APK signed with the same certificate updates in place and retains pairing, settings, and drafts. Self-built/debug APKs have a different certificate even when the package name matches; Android will reject an in-place update.

For a personal-debug → public-alpha migration, finish/check pending replies, record preferences, and revoke the old paired device on the backend. Uninstall the debug app, install the public APK, and pair again. Uninstalling loses phone-local drafts/settings; server-side tasks and Codex transcripts stay on the workstation. Do not uninstall a user's working build automatically. Independent distributions should use a distinct package/provider identity if they need to coexist; changing that also requires matching Firebase configuration and push package validation.

## Publication

1. Run `npm ci`, `npm test`, `npm audit --omit=dev`, and `node scripts/check-public-source.mjs` on the staged publication set.
2. Verify a fresh checkout can build. Run signed release build and inspect `android:debuggable`, version, certificate, and APK contents.
3. Install the signed APK in a clean Android test environment. Verify pairing, rendering, process restart, and upgrade behavior as applicable. Record tested limits honestly in VERIFICATION.md.
4. Tag the reviewed commit, publish source under the MIT license, and attach only the signed APK, checksum, and certificate details to a GitHub prerelease. Never attach runtime state, keys, private screenshots, or R8 mapping files containing local source information.
5. Check public unauthenticated access and GitHub Actions results. Maintain the same signer on future releases.

# Android releases and upgrades

## Public identity

Public APKs use package `co.fallsoft.pocket`, a stable private FallSoft signing key, and monotonically increasing version codes. The release includes `SHA256SUMS.txt` and `SIGNING-CERTIFICATE.txt`. Verify both the file checksum and signer before installing/updating:

```bash
sha256sum -c SHA256SUMS.txt
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --verbose --print-certs pocket-0.5.0-alpha.5.apk
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
export NEXTCOMP_BUILD_HOST=your-verified-authorized-build-host
export POCKET_RELEASE_STAGE=/private/new-release-stage
node scripts/build-release.mjs
unset POCKET_SIGNING_PASSWORD
```

For distinct key/store passwords, set `POCKET_SIGNING_KEY_PASSWORD` too. Do not put passwords in shell history, git, Gradle properties, logs, or release assets. Keep a secure off-machine backup of the signing key and credentials; losing them prevents updates under that identity. The signing script requires explicit credentials and has no debug fallback.

Unsigned CI builds validate compilation and lint; they are not installable releases and are never uploaded as release APKs. Original release builds run on the authorized build host with the private signing identity. Pin package/Android/backend versions together and increase `versionCode` before each release.

## Upgrade and migration

A later official APK signed with the same certificate updates in place and retains pairing, settings, and drafts. Self-built/debug APKs have a different certificate even when the package name matches; Android will reject an in-place update.

For a personal-debug → public-alpha migration, finish/check pending replies, record preferences, and revoke the old paired device on the backend. Uninstall the debug app, install the public APK, and pair again. Uninstalling loses phone-local drafts/settings; server-side tasks and Codex transcripts stay on the workstation. Do not uninstall a user's working build automatically. Independent distributions should use a distinct package/provider identity if they need to coexist; changing that also requires matching Firebase configuration and push package validation.

## Publication

1. Run `npm ci`, `npm test`, `npm audit --omit=dev`, and `node scripts/check-public-source.mjs` on the staged publication set.
2. Verify a fresh checkout can build. Run signed release build and inspect `android:debuggable`, version, certificate, and APK contents.
3. Install the signed APK in a clean Android test environment. Verify pairing, rendering, process restart, and upgrade behavior as applicable. Record tested limits honestly in VERIFICATION.md.
4. Tag the reviewed commit, publish source under the MIT license, and attach only the signed APK, checksum, and certificate details to a GitHub prerelease. Never attach runtime state, keys, private screenshots, or R8 mapping files containing local source information.
5. Check public unauthenticated access and GitHub Actions results. Maintain the same signer on future releases.

## Updates without ADB

The release builder prepares verified APK variants in the new private `POCKET_RELEASE_STAGE/app-updates` staging directory. It does not write the live update channel. The manifest includes the actual APK package/version, SHA-256 and signing certificate. Stable release APKs remain the public GitHub artifacts; compatible development-signed APKs are only served to paired devices through authenticated endpoints. Do not upload development APKs or the private runtime directory.

Once the new backend is deployed, announce the prepared update with:

```sh
node scripts/announce-app-update.mjs
```

Announcements are deduplicated per build/device. NextComp selects its installed signing identity, downloads and verifies the APK, and offers an Install notification and Settings control. Android requires allowing NextComp as an installation source once and confirming the system installer. No ADB connection is needed for subsequent updates. Existing pairings are retained.

## Checked incremental-alpha publication

The earlier manual publication path could overlook a native failure because
`am instrument` can exit zero while printing `FAILURES!!!`. Use the checked path
for future releases. The gate verifies the actual final log, one successful test
summary, source/test-input fingerprints, APK hashes, version, build tasks and
required case receipts. It rejects missing, failed, stale or altered evidence.

`build-release.mjs` runs both unit variants, both lints, both signed APK builds
and separate qualification/test APK builds. It binds receipts to the APK's actual
version, rather than the package-manager metadata. Configure the authorized build
host and a new private stage; the builder also checks the 80 GiB recovery reserve.
Credentials stay outside source. For an already paired private QA emulator, set `POCKET_QA_SIGNING_STORE` to its existing debug-compatible signing store; do not erase its data to resolve a signer mismatch. Set the maintained compatible debug signing store
when that installed identity is an intended update recipient.

Create an explicitly owned `nextcomp_env_qa` emulator on serial `emulator-5620`.
Its private owner receipt records the actor, live emulator PID/start ticks and
owned fixture PID. Set `NEXTCOMP_QA_OWNER_RECEIPT`, `NEXTCOMP_QA_OWNER` and the SDK
path. For each case in the staged `policy.json`, run:

```sh
node scripts/record-native-qualification.mjs "$POCKET_RELEASE_STAGE/app-updates" "$NATIVE_CASE"
```

The recorder verifies current ownership, creates a fresh synthetic fixture per
case, installs only into that emulator and records the installed artifact hash.
Its children receive no signing passwords or administrative configuration. An
existing intent is inspected rather than automatically replayed. Clean up only
the verified owned fixture/emulator after qualification. Physical phone input,
provider speech and optimized runtime behavior remain separate evidence.

Read the current live channel version and supply it explicitly:

```sh
node scripts/publish-qualified-update.mjs "$POCKET_RELEASE_STAGE/app-updates" "$PRIVATE_UPDATE_CHANNEL" "$EXPECTED_CURRENT_CODE" --check
node scripts/publish-qualified-update.mjs "$POCKET_RELEASE_STAGE/app-updates" "$PRIVATE_UPDATE_CHANNEL" "$EXPECTED_CURRENT_CODE"
```

Only qualified signing variants are copied; qualification APKs and test logs are
not served as updates. Artifacts are checked before the atomic manifest change.
A version compare-and-swap, publication lock and durable intent prevent ordinary
competing publishers or accidental retries from silently replacing the channel.
After an uncertain publication, inspect the journal, live manifest and artifact
hashes; do not rerun it. This trusted operator pipeline is not an administrative
security fence against arbitrary filesystem writes.

The policy is an explicit incremental regression set, not certification of every
UX requirement. Extend it for each change. Known open immersion reflow and real
handset qualification remain open; passing this gate cannot claim world-class UX
is finished.


Release builds reset only their own disposable APK packaging caches and output
APK files before assembling. This avoids dead ZIP gaps left by incremental
packaging; it does not remove dependencies, source, staged qualified artifacts
or published updates. The build intent records the exact reset set. Signing,
version checks, APK limits and native review remain required.


When the release policy requires optimized review, publication also requires
artifact-bound native search/Team observations and a completed visual review.
Readable selected identities are explicit: passing routing tests cannot excuse
controls which hide their destination. The review records the exact optimized
APK, source/test fingerprints and capture digests; missing, rejected, stale or
changed evidence blocks both the check and publication paths. This is a trusted
operator attestation, not an automated aesthetic score or proof of handset use.

Qualification may use the explicit emulator serial and AVD name in its owner
receipt. The runner verifies the exact live emulator PID/start ticks, AVD and
console port; physical phone identifiers are rejected. Use a dedicated owned AVD
when a historical service owns the old test serial. Preserve its original
receipt and processes.

### Maintained phone-compatible signing

Phone-compatible updates must also use the optimized `release` build type. Set
`POCKET_PHONE_SIGNING_STORE`, `POCKET_PHONE_SIGNING_PASSWORD`, and
`POCKET_PHONE_SIGNING_ALIAS` privately alongside the public release identity.
The builder preserves the public signed APK, then signs the same release
configuration with the installed phone identity and prepares both variants.
Supplying `POCKET_DEBUG_STORE` without these release credentials fails closed;
the debug build must never be substituted merely to retain an installed signer.
Changing debuggability does not require uninstalling or re-pairing when package,
signing identity and monotonic version are retained. Qualify the optimized
artifact separately; a non-shrunk instrumentation build is not its runtime proof.

## Native preflight before immutable signing

Run the complete finite set of affected native journeys against one qualification
APK before starting the immutable signed builder. A footer/navigation change
affects reconnect, recording, drafts and controls, even when its focused screen
check passes. Do not discover each stale selector in a new signed release build.
After correcting a journey, rerun that journey, then the affected set together.

Retain pairing and unrelated drafts. Synthetic fixtures must explicitly save,
initialize and restore only their own scoped data; they cannot assume retained
app data is empty. Assert user concepts (mounted conversation, source identity,
footer controls and saved draft), not a header that the product intentionally
removed. Real QA operations retain journals and stable identities. Read a settled
original operation rather than resend it; a new finite QA operation requires its
own identity and proof earlier work is terminal.

A failed immutable qualification stays failed. Test-only corrections require new
test-input attestation; do not edit old receipts or publish with stale evidence.
The signed build, final native gate and optimized APK review remain required.

## Current CPU storage admission

The user-directed October22 override is8GiB plus measured additional build bytes and concurrent reservations, superseding historical80GiB wording above. `storage-build` retains the actual capacity check; native build trees and caches remain disk-backed.

### Retained QA devices using the maintained certificate

When the owned emulator is already signed with the maintained release identity,
set `POCKET_QA_SIGNING_STORE`, `POCKET_QA_SIGNING_PASSWORD` and
`POCKET_QA_SIGNING_ALIAS` privately to that existing signer. Optional
`POCKET_QA_SIGNING_KEY_PASSWORD` supports a distinct key password. Only the
qualification variant uses this explicit QA configuration. Shipping release
signing and the phone-compatible optimized variant stay separate. A store-only
QA setting retains the historical debug-compatible behavior.

Check the installed and candidate certificate before installation. This does not
authorize uninstalling the owner's phone or erasing retained QA preferences to
solve a signing mismatch. Record the test artifact as debuggable qualification,
never as an optimized release or physical-phone verification.

# Verification

## Tested platform

Stock Codex CLI **0.157.1**, Linux, Node **22.23.1**, and Pixel 9 Pro Fold on Android **API 36**. The shared Unix app-server was used without a fork or competing runtime.

## Physical-device evidence from 0.4

- Created a read-only session using the actual New task screen. Codex inherited the workstation model, emitted commentary, ran `pwd` and a marker-print command, and finished successfully. No project files were modified.
- Visually checked chronological prompt, commentary, command, expanded output, final response, and completion. Earlier reading position stayed put during new live activity; Latest returned to live output.
- Loaded a long conversation with more than 500 rows and paged to its earliest turn without overlap.
- Delivered a Firebase notification after verifying the Android process was absent. Android started the app process and displayed the notification. No permanent foreground service or battery exemption was present.
- Replied through a real notification into the original active Codex turn. The server recorded acceptance and the phone showed the final sent status.
- Generated all five generic spoken labels using an offline English voice. The user confirmed hearing the question label.
- Exercised a persisted reminder worker after its delay by explicitly triggering the eligible Android job. This verifies worker execution, not exact natural five-minute delivery. The reply resolved attention and stopped further reminders.

## Public alpha checks

Release **0.4.1-alpha.1**, Android version code **5**:

- Nine backend tests pass in the working checkout and an isolated source export with newly installed dependencies. Coverage includes pairing/replay protection, owner/device separation, revocation of an open WebSocket, unauthorized attachment/socket access, local-only owner commands, configured pairing paths, durable reply/session IDs, exact request routing, interruption, push retries/project isolation, transcript merging, and paging.
- `npm audit --omit=dev` reports zero known vulnerabilities at preparation. A transitive UUID dependency was pinned to its compatible fixed version.
- Signed `assembleRelease` and `lintRelease` pass. Lint has advisory warnings (including dependency freshness, intentional synchronous durable preference writes, and the generic-sounds provider), with no errors. The Fragment dependency was upgraded to fix permission ActivityResult compatibility. Android 12+ backup and device-transfer exclusions are explicit.
- The optimized APK installed on a freshly reset Android API 35 emulator with Google Play services. First-run UI was inspected; authenticated HTTPS pairing completed and Firebase registration became ready.
- A real FCM message arrived while the release app was in the background. Its receipt log and Android active-notification record agreed. This smoke test does not establish process-death delivery; the separate physical 0.4 test above does.
- Reinstalling the same signed APK retained pairing.
- The release rendered the existing read-only verification conversation in chronological order. Android rejected `run-as` because the package is not debuggable.
- `apksigner verify` confirms the maintained FallSoft certificate and APK Signature Scheme v2. Source and APK scans found no private key/owner-token values or personal Firebase/server configuration. Only an empty first-run screenshot is included in public source.
- GitHub Actions independently runs backend tests/audit/source checks and an unsigned Android release/lint build. See the repository's Actions tab for exact run outcomes. Controlled app-server fixtures are not equivalent to live approvals.

## Limits

Physical reply evidence covers an active turn. Idle continuation, approvals, and stop-task routing use controlled protocol fixtures; no permission request was induced in ongoing user work. Other physical phone vendors, overnight Doze, full 30-minute snooze, reboot recovery, and other Codex versions are unverified. Force-stop prevents push until reopening. FCM acceptance alone never proves phone delivery. This is not an independent security audit or broad compatibility certification.

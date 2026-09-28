# Verification

## Tested platforms

Stock Codex CLI **0.157.1**, Linux, and Node **22.23.x**. Physical testing covers a Pixel 9 Pro Fold on Android **API 36**; the public release also runs on an Android **API 35** emulator with Google Play services. The shared Unix app-server was used without a fork or competing runtime.

## Display cache limits: 0.4.4-alpha.2

Verified on September 28, 2026:

- **60 backend tests** pass. Cache stress covers 30 conversations with 50 tool events each, binary payload exclusion, byte/thread/item limits, idle expiration, and explicit clearing. Fresh snapshots replace stale cache entries; an evicted streaming prefix requests a reload instead of showing a suffix as the complete message.
- A long-turn regression traverses every original item exactly once across bounded pages while a new item arrives. Cursors anchor history positions rather than offsets into a changing latest page. Individual large display text is explicitly marked as an excerpt; the source data is unchanged.
- The 8 MiB budget counts serialized live display entries, not total process heap. JavaScript objects, bounded metadata, upstream frames, and temporary request buffers also use memory. The existing 100 MiB individual upstream-message limit and legacy full-history limitation remain.
- The phone replaces earlier pages rather than accumulating them, offers Back to latest, bounds live rows and coalesced in-flight updates, and releases the transcript on task exit or Android memory-pressure callbacks. Completion recovery retains cursors instead of whole summary pages during its boundary scan.

## Paged history and connection errors: 0.4.4-alpha.1

Verified on September 28, 2026, with the installed stock Codex CLI **0.158.0**:

- Reproduced the reported failure against a real large conversation: full-history reading exceeded the WebSocket client's 100 MiB frame limit and closed Pocket's connection. The original error handler discarded that cause and returned “Codex disconnected.”
- Used the installed CLI's generated schema to verify metadata-only reads/resumes and turn/item pagination. These are described in the [official app-server documentation](https://learn.chatgpt.com/docs/app-server). Both affected conversations now return their recent and preceding history pages successfully through the deployed backend, while its Codex connection remains open.
- The phone receives display content rather than inline image data, raw reasoning, or unbounded command output from paginated history. Live deltas retain chronology, and older pages do not acquire unrelated live turns.
- **57 backend tests** cover both paginated and legacy history, cursor traversal, actual HTTP endpoints, oversized-frame diagnostics, reconnects without replaying writes, and completion recovery beyond a page of missed turns. The automatic reload path performs reads; ambiguous replies remain marked unknown.

The connection fix does not remove the 100 MiB limit on individual upstream messages. Exceptionally large individual items and legacy full-history responses can still fail with a specific explanation. Native approvals retain the existing reconnect limitation below. This targeted 0.158.0 validation does not repeat every earlier fresh-install, approval, and Firebase test.

## DNS recovery: 0.4.3-alpha.3

Verified on September 28, 2026, with Android version code **9**:

- Four Android resolver tests cover a saved route surviving resolver failure and a new resolver instance, preference for working system DNS, IPv4/IPv6 Tailscale address bounds, and rejection of unrelated hosts, public/LAN addresses, and malformed cache entries. DNS failures are injected in these tests.
- API calls, WebSocket connections, and Coil attachment loading use the same resolver. Routes are saved after an authenticated HTTPS connection to the configured workstation. The URL hostname, default certificate validation, and hostname verification remain unchanged.
- Upgraded the Pixel in place, preserving pairing and Speak summaries. The app connected and automatically saved the workstation’s authenticated Tailscale route. Reopening after process termination retained that route and loaded the active conversation. DNS was already working again during this device check; forced DNS failure is covered by the resolver tests above.
- **51 backend tests** pass; Android debug/release builds, unit tests, release lint, and maintained-certificate verification pass. No lint errors; advisory warnings remain.

The fallback requires a previously saved route and does not repair a disconnected VPN, expired HTTPS certificate, or stopped workstation. Initial pairing still needs working DNS.

## Recovery and spoken summaries: 0.4.3-alpha.1

Verified on September 27, 2026, with Android version code **7**:

- A controlled app-server test follows a running task, stops the Pocket process, completes the task while disconnected, and restarts Pocket against the same database. Exactly one completion notification is recovered. Another restart and a replayed completion event do not duplicate it. An inaccessible watched task does not block recovery of another task.
- Recovery tests cover existing-history baselines, offline subscription boundaries, failed turns, deliberate interruption, and unfollow/refollow behavior. Notification persistence and push enqueue share a database transaction; completed turns have a unique notification identity.
- Upgraded the owner's Pixel in place, retaining pairing and preferences, and enabled Speak summaries. A real Firebase notification caused offline TTS playback. The owner confirmed hearing the speech.
- Held Do Not Disturb active until a separate notification was confirmed received; no speech started. Restored the original setting, confirmed the Android app process was absent, then sent another FCM notification. Android restarted Pocket, the TTS engine reported playback completion, and the playback service stopped. The owner confirmed this corrected check worked. The first attempted quiet-mode check ended before confirming delivery and is not counted as evidence.
- Spoken text uses an optional explicit summary or a bounded, cleaned excerpt. Tests verify removal of code/markup/URLs, content bounds, and total FCM payload size. Historical catch-up and reminder delivery do not start automatic speech.
- **51 backend tests** pass. Android debug/release builds and release lint pass. The phone retained its existing debug signing identity; the separate public APK uses the maintained FallSoft release certificate.

Native approval requests still belong to their original bridge connection and must be handled in the terminal if that connection is lost. Speech is optional and falls back to the notification tone if an offline voice, audio focus, or background-start permission is unavailable. This is not a guarantee of delivery under Android force-stop.

## Fresh-install and upgrade check: 0.4.2-alpha.1

Verified on September 27, 2026, with Android version code **6**:

- Started from a new Debian 12 container with Node **22.23.3**, a public HTTPS repository clone, `npm ci`, and a newly installed stock Codex CLI. Only the owner's Codex authentication was supplied; no existing Codex configuration, history, Pocket data, or host source directory was mounted. Opening the normal Codex TUI created its shared runtime. The new preflight passed before Firebase or Pocket was running.
- Created a separate Firebase project on the free plan, registered Pocket with the documented setup flow, and retained a sender whose sole project permission was `cloudmessaging.messages.create`. Removed the temporary setup administrator after configuration. The full doctor passed with the backend running.
- Installed the previous public signed APK on a wiped API 35 emulator, paired through the actual app UI over private HTTPS, and confirmed registration against the new Firebase project. Started a task from New task and received its final response and completion notification.
- Upgraded to the signed **0.4.2-alpha.1** APK without uninstalling. Pairing survived, the release certificate matched, and Android reported a non-debuggable package. No personal server or Firebase configuration is embedded in the APK.
- Answered a real stock-Codex command approval through **Allow once**. The requested test file was absent before approval and contained the expected text afterward; the pending request resolved. This was isolated test work, not an approval in the owner's existing projects.
- Replied through Android's notification **Reply** action to an idle session. The outbox recorded one accepted reply, Codex completed the continuation with the requested marker, and Android displayed both the sent status and the resulting completion notification.
- Confirmed the release app process was absent, sent a notification through the fresh Firebase sender, and observed Android start the process and display that notification. This was a background process-death test, not force-stop or overnight Doze.
- Rejected a nonexistent project folder with a clear error and a usable **Start task** button, selected a valid folder, then started successfully. Tapped **Stop task** during a running command and observed the turn become **Interrupted**.
- All **47 backend tests** pass locally and in the isolated Linux environment. `npm audit --omit=dev` reports zero known vulnerabilities. Signed `assembleRelease`, `lintRelease`, signature verification, and the public-source scan pass. The release includes checksums and the maintained signing certificate fingerprint.

The fresh-install exercise used automated interaction and an existing Codex account. It is not a usability study with new human users. The test Firebase project and Linux environment are disposable; they are not required services for other installations.

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

Physical reply evidence covers an active turn; the isolated 0.4.2 exercise additionally covers idle continuation, a live command approval, and stopping a running task. No permission request was induced in ongoing user work. Other physical phone vendors, overnight Doze, full 30-minute snooze, reboot recovery, and other Codex versions are unverified. Force-stop prevents push until reopening. FCM acceptance alone never proves phone delivery. This is not an independent security audit or broad compatibility certification.

Device check for alpha.2: in-place debug upgrade to version code 11 succeeded on the paired Pixel. A previously failing large conversation loaded 383 display rows with an empty event buffer. Pairing token hash, device ID, server, and spoken-summary preference matched the pre-upgrade values. Physical earlier-page/back-navigation checks were not completed because the phone was locked; pagination and replacement have automated coverage. Signed release build, six Android unit tests and release lint passed.

## Webhook delivery-history pagination correction

The recovery audit failed once delivery history exceeded its first 100-record page: GitHub supplied a `Link: rel="next"` URL under `/repositories/<numeric-id>/...`, while the adapter accepted only `/repos/<owner>/<name>/...`. The listener continued accepting new signed events, but the audit correctly marked health unhealthy. The adapter now resolves the configured repository's ID, verifies numeric links against it, and normalizes pagination back to the configured repository route. Pagination must stay on the same collection and HTTPS API origin; other repositories, endpoint changes, credentials, and fragments are rejected. Regression coverage exercises the actual HTTP adapter through a second page and exact 64-bit redelivery ID, plus hostile links and the existing bounded-history failure. All 63 backend tests passed. No phone update is required for this server-side correction.

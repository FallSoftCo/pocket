# Verification

## Reply recovery and Important conversation filter: 0.5.0-alpha.23

Failed and uncertain outgoing input has direct Retry, Edit and Remove. A definitely rejected message is queued again only by explicit user action, with its original stable ID and mode. Unknown delivery requires a separate duplicate-risk acknowledgement. Accepted/in-flight input is protected, and removal during a failed asynchronous attachment cannot resurrect a cancelled row.

Important filters the existing transcript: retained answers, final replies, pending questions and failures remain, while routine progress is excluded. Retained source passages outside loaded history appear in the same viewport, labeled as excerpts with direct navigation to surrounding history. The composer remains shared; one compact control starts/stops speech using actual displayed visible assistant text. Unsave removes retention metadata without deleting the original conversation.

Validation: 199 backend tests, 106 Android tests, debug/release lint and maintained signing passed. Thirteen actual native tap scenarios on the exact APK include successful removal and editing while history returns 503, Important/All without changing draft or keyboard, source navigation, Unsave, orphan sources, uncertain-delivery confirmation and single Speak/Stop. See [synthetic runtime qualification](qualification/recovery-important-runtime-alpha23.json). Physical phone delivery is recorded separately; emulator speech checks do not establish acoustic quality. At 2× text the selected filter label ellipsizes while remaining accessible and tappable. Alpha22 evidence below applies to that earlier layout.

## Consolidated conversation context: 0.5.0-alpha.21

Retained answers and equivalent speech from the same known conversation share one context surface above the composer. Reply and source navigation remain direct alongside Pause/Resume and Clear. The count opens all retained answers, where Remove remains available. Different-source or extended speech stays visible. The composer preserves its complete draft while showing at most three lines; coordinator typing places a compact microphone beside the editor.

Validation: 184 backend tests and 77 Android tests passed, with zero dependency vulnerabilities. Debug and maintained-key signed release builds and lint passed. Synthetic API35 emulator checks cover normal, 2x text and narrow layouts, keyboard/draft preservation, actual authenticated Enter submission, notes navigation, failed-remove rollback and successful retry, and in-place Italian phrase toggling. See [runtime qualification](qualification/conversation-context-alpha21.json). The repository screenshot is explicitly synthetic. This qualification does not establish phone installation or real-provider audio quality.

## Tested platforms

Stock Codex CLI **0.157.1**, Linux, and Node **22.23.x** for workstation mode. Phone-local testing uses stock Codex CLI **0.158.0**, Node **26.1.0**, Termux **0.118.3**, and PRoot on a Pixel 9 Pro Fold with Android **API 36**. The public release also runs on an Android **API 35** emulator with Google Play services. No Codex fork is used.

## Composer layout and Enter: 0.5.0-alpha.7

The composer is one rounded input area with one main button. An empty field shows Stop while a turn is running; entering text shows Send/Steer. Holding that button queues input. Queue count opens a management sheet with edit, send-now, remove and resume actions. The always-visible instruction row, second dropdown button, and inline queue action rows were removed. Stop now acts immediately and retains the bridge’s queue-pause behavior.

Enter and the Android keyboard’s Send action submit the message. Shift+Enter inserts a line break. Empty Enter does not interrupt a task. Backend queue and permission behavior is unchanged.

Validation: Android debug build and lint passed. Synthetic API35 emulator checks confirmed single-row composer layout, long-press queueing, queue-sheet navigation, Enter sending one accepted message, and Shift+Enter preserving a newline inside one accepted message. Release build and upgrade checks are recorded in the release notes.

## Conversation controls: 0.5.0-alpha.6

The composer labels running-turn input as Steer. Holding its button queues a follow-up, with an explicit menu option and accessibility action for the same operation. Queue entries can be edited, removed, or sent immediately. Stop is visible beside the composer and pauses pending queued messages; interruptions and failed turns also pause them. Resume is explicit. The bridge persists queue mode/order and distinguishes a definite busy rejection from an unconfirmed delivery.

Conversation actions expose model and reasoning effort from the connected Codex catalogue, plus Plan/Build mode, rename, archive and restore through the Archived filter. Saved model/effort/mode settings apply to future `turn/start` requests, never an active steer. Archiving requires an idle conversation and leaves its queue paused after restoration.

Validation: all 89 backend tests passed, including HTTP tests for queue order, edit/remove, steering promotion, stop/resume, failure/restart recovery, definite busy rejection, unknown delivery without duplicate sends, next-turn overrides, rename and archive/restore. Generated stock Codex 0.160.0 schemas confirm the request shapes; a live read-only model catalogue check succeeded. Android unit tests, debug build and debug lint passed. An isolated API35 emulator with synthetic task data verified long-press queue creation, queue controls, stop confirmation, paused-queue display, effort selection and saving Plan mode. Release build/signature checks and paired-phone deployment are recorded in the release notes.

## New-task permissions and spoken context: 0.5.0-alpha.5

Android keeps the existing package and signing identity so upgrades preserve pairing, preferences and drafts.

New tasks default to `danger-full-access` with approval policy `never`. The Android Settings and New task screens share a saved full-permissions switch. Turning it off selects `workspace-write` with `on-request` on the workstation; Termux keeps its required full filesystem access with approval requests. Permissions are persisted with the idempotent creation request and reapplied on resume. Existing loaded Codex threads can ignore resume overrides; running conversations require interruption and a new turn to apply changed permissions.

Validation before rename: all 79 backend tests passed, including permission defaults, invalid requests, idempotency conflicts and recovery with selected permissions. Android unit tests, debug build and debug lint passed. Live stock Codex 0.159.2 returned `dangerFullAccess` and approval policy `never` for a fresh empty test thread, which was archived without running a task. The development APK upgraded the paired phone in place. Notification speech now prepends a three-word prompt-derived context rather than the conversation title. The notification MCP accepts a model-written `spoken_context`; automatic completions use their own turn’s latest user input, with a compact keyword fallback and a neutral label when input is unavailable. Context is saved per subscribed thread for live alerts and survives bridge restarts. Full response speech still uses deferred authenticated retrieval beyond push limits.

Speech and permissions validation: all 83 backend tests passed, including authenticated notification API speech/context checks and recovery. The additional paginated-input speech test passed. Dependency audit reported zero vulnerabilities. Android unit tests, debug build/lint, and signed release build/lint passed; the release maintains the documented signer, package identity and version code 18. Version code 18 keeps the maintained certificate fingerprint. The temporary Pocodex branding was withdrawn before release because of an existing remote-Codex project with that name. A signed candidate installed in the isolated API35 emulator. No new claim of physical visual review is made.

## Persistent weekly allowance: 0.5.0-alpha.4

The Android app keeps a weekly allowance strip above onboarding, all tabs, new tasks, and conversations. It shows remaining percentage, a progress bar, and the reset time in the phone's timezone. Low allowance changes the accent color. Cached values are profile-specific and explicitly marked last known when disconnected or stale; unavailable allowance is distinct from zero remaining.

The backend reads stock Codex `account/rateLimits/read` and merges `account/rateLimits/updated` events. The weekly window is identified by its 10,080-minute duration in either primary or secondary; account identity and credit fields are excluded from the client payload. Reads are cached and deduplicated, account changes clear the cache, and live events received during a read take precedence.

Validation: 78 backend tests passed, including an authenticated HTTP/WebSocket integration test; Android unit tests, debug and signed release builds, release lint, public-source checks, and diff checks passed. The workstation bridge was updated and returned a real weekly remaining value of 17%. The paired Pixel was upgraded in place to version code 17 using its existing development signing identity. Physical visual review is pending because the phone is PIN locked.

## Preapproved phone tools: 0.5.0-alpha.3

The Termux installer now marks all seven `pocket-phone` tools approved after the owner enables Android Accessibility and Pocket's independent phone-control switch. This removes per-tap, per-scroll, per-text, and navigation approval interruptions during an explicitly requested phone task. Android secure surfaces, password-field refusal, loopback authentication, and the requirement for direct instructions before consequential actions remain in place.

## Profile-specific task folders: 0.5.0-alpha.2

The phone profile no longer inherits the workstation's last project folder or pending new-task state. The local bridge advertises Termux's real home directory, and the physical New task screen defaulted to `/data/data/com.termux/files/home`. Workstation and phone project folders, prompts, and saved creation requests now use separate preference keys. The main session screen also exposes a persistent Workstation / This phone switch when both profiles are paired.

The development APK upgrade cleared Android's enabled Accessibility-services list while leaving Pocket's independent automation switch enabled. After restoring the Android service, an authenticated loopback snapshot returned the foreground package with 24 nodes. A new real phone-local Codex turn then called `phone_screen` through the configured MCP server and completed with the correct foreground package. A disconnected service now returns an explicit instruction to re-enable Pocket in Android Accessibility rather than a generic fetch failure.

Android does not let an ordinary app silently enable its own Accessibility service. Pocket now polls the independent system gate while its local monitor runs. If an update clears that gate, the main workflow shows a persistent Restore control and the foreground notification opens Android Accessibility directly. The in-app switch no longer appears enabled while the system service is off.

## Phone-local Codex and control: 0.5.0-alpha.1

Pocket's Android profile paired with a bridge at `127.0.0.1:18880`, listed the phone's real Codex sessions, and retained the existing workstation profile. A real app-server turn created and read a local proof file through stock Codex. The installed CLI reports paginated history capability but rejects `thread/turns/list`; Pocket detected that runtime mismatch and loaded the turn through bounded `thread/read` fallback.

The physical Pixel bound Pocket's Accessibility service after both controls were enabled. An authenticated loopback snapshot reported the foreground Pocket package and 42 useful nodes. Screenshot capture returned a valid 181,967-byte PNG. A Home action succeeded, and the next authenticated snapshot reported the Pixel Launcher with 74 nodes; Pocket was then reopened. An invalid or missing secret is rejected, the in-app switch can pause all calls, password text is omitted, and password fields reject entry by construction.

An in-place development upgrade correctly preserved 13 queued spoken updates but left the queue in its explicit saved state. After Resume, Android reported active playback. A normal System UI notification then delivered `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` followed by gain; Pocket stayed in `PLAYING` before and after the two-second chime. This reproduces and fixes the prior behavior where any negative focus event permanently paused the queue. Transient loss now resumes automatically, ducking only lowers volume, and permanent loss still saves the exact playback position.

Codex CLI discovered `pocket-phone`; its default noninteractive `never` approval policy correctly refused the first unconfigured read. The installed configuration explicitly approves only `phone_screen` and `phone_screenshot`; tap, click, scroll, text, and navigation keep the normal approval path. After configuration, Pocket started a real phone-local Codex session whose model called `phone_screen` and returned the foreground package `co.fallsoft.pocket`. The turn completed idle through the Android UI bridge without a Codex fork.

Validation: 72 backend tests and 14 Android unit tests pass. Debug and signed release builds, release lint, maintained-certificate verification, clean-source validation, in-place installation, and the physical checks above pass. GitHub Actions independently repeats the public test and build checks for the tagged commit.

## Resumable on-device speech: 0.4.4-alpha.4

Speech now synthesizes one private offline audio chunk and plays it through Android MediaPlayer, with media-session Pause controls and a saved Resume notification. The in-app player exposes the same controls and a separate menu to clear saved speech. Pausing stops the service and releases audio focus; notification text, the current audio file and playback position survive process recreation. Incoming speech remains queued while paused. Completed chunks are deleted; disabling speech or disconnecting clears saved content. No overlay permission or cloud speech service is used.

On the physical Pixel, manual Pause saved a position of 17,689 ms. An in-place upgrade preserved that queue; Resume started at exactly 17,689 ms. A separate, temporary foreground app then requested music audio focus and played silent local audio. Pocket paused at 17,694 ms, kept both queued messages and stopped its service. The test app was removed afterward. An earlier process-death/relaunch check also preserved the current chunk, its 10,683 ms position and all queued IDs. Pairing, device identity and Speak messages remained intact. The player and paused notification were visually inspected on the phone.

Validation: 68 backend tests, 14 Android unit tests, debug and signed release builds, and release lint passed (zero lint errors; advisory warnings remain). Queue tests cover late completion callbacks after pause, duplicate arrivals, message ordering, restored multi-chunk progress and retention beyond the old three-message queue cap. Audio is checkpointed every two seconds during playback; abrupt process death can replay the interval since the last checkpoint. Explicit Pause saves its current position immediately. This does not claim speech can continue during Android force-stop or after clearing app data.

## Full-message speech: 0.4.4-alpha.3

The server preserves the complete cleaned spoken text separately from the short compatibility preview. The optional `spoken_summary` input now accepts the same 32,000-character budget as notification messages. The phone plays all text in ordered chunks, preferring sentence boundaries and retaining long sentences and Unicode without dropping the ending. Initialization and each utterance have separate stall watchdogs; there is no shared 30-second playback deadline. TTS queueing does not flush an active utterance. Stop, audio-focus loss, mute and Do Not Disturb remain intentional interruptions.

Short speech travels directly in FCM data; longer speech is fetched from the authenticated notification-by-ID endpoint. Notifications still display immediately. If the workstation is unreachable, the phone reads the compatibility preview and explicitly says the full spoken message is unavailable. Old app versions still receive a short preview. Nothing is sent to a cloud speech provider.

Validation: 68 backend tests and 11 Android unit tests passed, including lossless multi-chunk playback text, exact full-message storage/retrieval, unauthorized retrieval rejection, sentence-complete compatibility previews and escaped/Unicode push payload budgets. Signed release build and lint passed. The paired Pixel was upgraded in place to version code 12 with pairing and its speech preference retained. A real 727-character, 125-word Firebase notification required full-text retrieval and played in two chunks (538 + 189 characters): start 14:26:45.474, final completion 14:27:28.698 on the device clock, approximately 43.2 seconds. Both utterance completion callbacks arrived and the playback service exited. This establishes playback completion, not a subjective voice-quality judgment.

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

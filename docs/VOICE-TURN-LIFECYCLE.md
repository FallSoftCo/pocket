# Voice turn lifecycle replacement

Status: durable audio intake and microphone cutover implemented in the owning candidate; focused native qualification is underway. Not deployed. See qualification/voice-turn-lifecycle-foundation-20261016.json.

The existing singleton pending slot couples capture, task delivery, receipt inspection and response playback. A turn can remain pending after the server accepted it solely because speech failed. Archiving that slot permits another recording but does not replace its ownership or recovery model. Alpha109 is containment, not qualification of this design.

## Ownership and boundaries

A capture owns an immutable turn ID, environment ID, original backend/pairing fingerprint and destination snapshot from microphone start. Destination includes room authority/audience/recipient or direct thread/coordinator intent. Navigation, profile selection and mutable device focus cannot retarget a sealed turn. Never store bearer tokens in journal rows; resolve the original pairing and verify its fingerprint before a request. Missing/replaced pairing requires explicit resolution, never substitution with the current profile.

Android owns microphone capture and durable audio. A transactional per-turn journal owns admission intent and receipt reconciliation. A delivery worker owns bounded network attempts. The backend owns durable admission and dispatch. Playback owns independent per-session response queues. Compose observes projections and issues commands; neither rendering nor playback decides whether task work was delivered.

Capture phases: recording, sealed, interrupted. Submission phases: unsubmitted, submitting, stored, accepted, unknown, completed, failed, cancelled. Stored confirms server audio custody, not task dispatch. Playback phases: unavailable, ready, playing, paused, completed, failed. These are independent dimensions, not a single state string. Only an actual recording owns the microphone. Pending, unknown or completed deliveries do not prevent a new capture. One foreground capture and one audible output remain the resource constraints; multiple durable turns are supported.

## Durable admission

Seal audio to a unique turn file using temporary write, fsync, rename and directory fsync. Commit the immutable identity, content hash and sealed-file reference transactionally before reporting it queued. Recover an interrupted file/row boundary explicitly; never claim queued for memory-only audio. Database I/O, hashing and file migration run outside the main thread.

The server admission key is authenticated device plus turn ID, bound to a digest of immutable destination and payload. Reusing a key with different content is a conflict. Persist admission before acknowledging it. A lost acknowledgement is reconciled by reading that same key. A dispatch attempt must be persisted before crossing the task boundary. Server restart does not turn an uncertain dispatch into a new submission. Accepted work, task completion and successful speech are distinct acknowledgements.

Native Codex audio remains on the existing signed-in account path. Do not introduce an API-key fallback or new billing/model choice. The installed experimental protocol exposes both WebRTC and websocket audio transport, but protocol shape alone is not runtime qualification. Select the transport only after a bounded actual audio-only round trip confirms final transcript/manual turn handling. No task dispatch may use a partial transcript. Preserve the audio interface prohibition on tools and delegated work.

Stock direct/coordinator execution persists the captured origin before controller startup; room execution validates its original binding and recipient. Both consume the original persisted destination. A per-device serial coordinator execution policy belongs in the server queue, not in microphone availability. Room task concurrency follows the canonical room backend contract.

## Recovery and cancellation

After process death, a submitting attempt becomes reconciliation-required, never implicitly unsubmitted. A missing HTTP receipt is not sufficient evidence that an external task was not delivered. Automatic resubmission requires a supported durable server admission boundary proving the same key is safe. Unknown legacy sends remain inspectable without automatic replay.

Use existing WorkManager for durable cheap reconciliation with bounded backoff and network constraints. No model polling, blanket session resumes or permanent foreground retry loops. Cancellation before dispatch can cancel local admission; cancellation after acceptance is an explicit original-target operation with its own receipt. Stopping playback does not cancel task work; leaving voice does not discard queued recordings.

Task response receipt commits completion independently of TTS generation or playback. Speech failure retains the text result and exposes retry speech only. It cannot resend the transcript. Playback is session-attributed, nonoverlapping and stale-aware; new recording may interrupt playback without invalidating delivery.

## Migration and release gates

Import the legacy pending WAV/preferences and alpha109 preserved recordings into source-bound journal entries. Migration is repeatable and verified by ID/content hash; retain old files until the new durable record and file are verified. Never migrate unknown ownership into the selected environment. Preserve original accepted and unknown receipts.

Required integration journeys use actual Android SQLite/files and process/service restart boundaries, plus the real isolated gateway: new capture while an older turn is unknown; TTS failure after accepted/completed work; lost admission acknowledgement; killed client before/after seal; gateway restart before/after dispatch; navigation and profile changes during capture; same ID with different payload rejected; cancellation; paired-voice provenance. Assert original request identities and no duplicate dispatch, rather than just counts of unit tests. Qualify the current native audio path and microphone hardware separately from synthetic HTTP fixtures.

Keep the compatible current APK and rollback closure available. Do not label an archive-only fix as the replacement, silently migrate pairings, or install an unqualified transport rewrite on the phone.

## Current transport qualification boundary

The microphone now captures to an independent per-turn journal and enqueues a WorkManager upload. It does not start a phone-owned transcription connection or consult an older pending task slot. Partial PCM is private and retained on interruption; only a verified sealed WAV becomes uploadable. The new recording-v1 journal is separate from legacy unknown sends, so a missing legacy receipt never authorizes a v1 upload. Explicit old saved-turn inspection remains separate.

Server admission durably seals the original WAV before acknowledging upload. A media-only worker returns a confirmed transcript; only the task admission component may submit it using the persisted original destination and principal. Queue processing is event-driven and receipt recovery uses metadata reads, not model polling. The Android projection distinguishes audio stored/transcribing from task acceptance. Completion commits before optional foreground response speech; mute and navigation are respected.

The selected peer is Python aiortc in an isolated pinned installation, communicating over bounded private JSON/media pipes. It waits for provider session.started before playing, drains the recording and silence tail, requests session.close, and keeps both channels open through session.closed. The pinned native Codex runtime then emits its canonical transcript/done notification. Both closure and that final transcript are required. Partial deltas and quiet-time heuristics cannot dispatch work. The rejected Node native addon is not a product dependency.

Actual audio-only native qualification returned a confirmed transcript and clean peer exit for the seven-second bundled synthetic sample (11.5 seconds direct; 11.9 seconds through the structured worker). No task turn, tool or external business action was available to that worker. The account remains the existing signed-in native Codex profile, without an API-key fallback. The command-backed adapter permits media processing on the authorized development host while task execution stays on the recording's original backend; that remote installation path still requires deployment qualification.

Actual emulator AudioRecord start/stop twice while the prior presentation state was Thinking passed without clearing pairing or preferences. Real Android SQLite/file reopen and unknown-delivery isolation also passed. These are not physical phone proof. Real canonical room admission and Android WorkManager/native ASR/room acceptance now passed using the retained isolated QA authority. The exact QA task was cancelled after verification; no business worker was created. Complete reliable response playback remains a release gate. No architecture APK has been published or installed on the phone.

The first real recording-admission HTTP journey retained its ID but stopped at the storage guard (507): the host was below its 80 GiB reserve. No audio processing or task started. Keep the reserve; do not label this as end-to-end delivery qualification.


## 2026-10-17 actual playback boundary

The candidate speech path produces source-attributed, integrity-verified WAV assets on the backend and plays them with Android's native player. Content/policy IDs and attempt receipts deduplicate listening; a lost asset download cannot replay task input. Completed clips remain reusable after listening stops. No background speech model retry is enabled.

This path is **not release-qualified**: a real render returned a confirmed 2.481-second clip in 8.754 seconds, but the subsequent actual Android journey failed when the native provider returned no audio. Both developer and user startup-history variants have shown silence. Startup context and append acknowledgements are not a deterministic TTS trigger. Do not present one successful render or the absence of a WebView as proof of reliable speech. Preserve the installed playback path until a supported, repeatably verified replacement exists; do not silently switch billing or audio models.

Capture limit/interruption is distinct from user stop: only an explicit stop sends the turn. At the two-minute safety limit private audio is retained without submission. Failure to acquire audio focus releases the capture wake lock immediately.


## Explicit saved-recording controls

The existing controls surface now lists this environment's independent recordings with original destination, age and state. Checking uses the original receipt worker. Cancellation retains custody and requires a canonical pre-dispatch cancellation when audio was admitted. Retry audio is offered only when the server proves a failed pre-dispatch media attempt; it retains the same audio hash, principal, destination and ID. Accepted task failure, unknown dispatch and revoked permission are not audio retry eligibility. Background refresh does not create a new task or start a speech model.

The installation-owned SSH audio worker has no task adapter or bearer token. Its private, digest-bound final-transcript cache makes a lost worker reply recoverable without another model generation. Actual initial remote transcription took 14.054 seconds; the repeated same recording returned the cached final result in 2.494 seconds. This qualifies the private CLI transport, not a production backend activation or physical-phone round trip.


## Coordinator task admission is also independent

`VoiceController.submitText` now durably admits each text command as queued with its immutable origin before starting model work. A per-device event-driven dispatcher serializes coordinator turns; microphone and media upload do not consult this execution queue. Completion or a new admission wakes the dispatcher, not a polling timer. The queue has a fixed 64-request admission bound. Queued cancellation is a compare-and-set transition; preparing/running work uses original task controls.

Preparing performs metadata/context work. Restart may requeue it only if no native-start intent was recorded. A recorded native-start intent or in-flight model turn becomes unknown and is never replayed. Restoring queued commands is explicit in the stock server startup lane; native Hollywood mode does not start stock controller work. Current original-device authority is checked before a queued command starts, and current permission restriction still wins over captured full access. A definite pre-admission rejection is distinct from an unknown task dispatch, avoiding false unconfirmed work and unsafe resubmission.

# Voice turn lifecycle replacement

Status: foundation implemented and qualified in the owning candidate; microphone/service cutover and migration are still required. Not deployed. See qualification/voice-turn-lifecycle-foundation-20261016.json.

The existing singleton pending slot couples capture, task delivery, receipt inspection and response playback. A turn can remain pending after the server accepted it solely because speech failed. Archiving that slot permits another recording but does not replace its ownership or recovery model. Alpha109 is containment, not qualification of this design.

## Ownership and boundaries

A capture owns an immutable turn ID, environment ID, original backend/pairing fingerprint and destination snapshot from microphone start. Destination includes room authority/audience/recipient or direct thread/coordinator intent. Navigation, profile selection and mutable device focus cannot retarget a sealed turn. Never store bearer tokens in journal rows; resolve the original pairing and verify its fingerprint before a request. Missing/replaced pairing requires explicit resolution, never substitution with the current profile.

Android owns microphone capture and durable audio. A transactional per-turn journal owns admission intent and receipt reconciliation. A delivery worker owns bounded network attempts. The backend owns durable admission and dispatch. Playback owns independent per-session response queues. Compose observes projections and issues commands; neither rendering nor playback decides whether task work was delivered.

Capture phases: recording, sealed, interrupted. Submission phases: unsubmitted, submitting, accepted, unknown, completed, failed, cancelled. Playback phases: unavailable, ready, playing, paused, completed, failed. These are independent dimensions, not a single state string. Only an actual recording owns the microphone. Pending, unknown or completed deliveries do not prevent a new capture. One foreground capture and one audible output remain the resource constraints; multiple durable turns are supported.

## Durable admission

Seal audio to a unique turn file using temporary write, fsync, rename and directory fsync. Commit the immutable identity, content hash and sealed-file reference transactionally before reporting it queued. Recover an interrupted file/row boundary explicitly; never claim queued for memory-only audio. Database I/O, hashing and file migration run outside the main thread.

The server admission key is authenticated device plus turn ID, bound to a digest of immutable destination and payload. Reusing a key with different content is a conflict. Persist admission before acknowledging it. A lost acknowledgement is reconciled by reading that same key. A dispatch attempt must be persisted before crossing the task boundary. Server restart does not turn an uncertain dispatch into a new submission. Accepted work, task completion and successful speech are distinct acknowledgements.

Native Codex audio remains on the existing signed-in account path. Do not introduce an API-key fallback or new billing/model choice. The installed experimental protocol exposes both WebRTC and websocket audio transport, but protocol shape alone is not runtime qualification. Select the transport only after a bounded actual audio-only round trip confirms final transcript/manual turn handling. No task dispatch may use a partial transcript. Preserve the audio interface prohibition on tools and delegated work.

Stock direct/coordinator execution currently reads mutable focus during controller run; room execution already has destination validation. Both must consume the persisted original destination before concurrent delivery is enabled. A per-device serial coordinator execution policy belongs in the server queue, not in microphone availability. Room task concurrency follows the canonical room backend contract.

## Recovery and cancellation

After process death, a submitting attempt becomes reconciliation-required, never implicitly unsubmitted. A missing HTTP receipt is not sufficient evidence that an external task was not delivered. Automatic resubmission requires a supported durable server admission boundary proving the same key is safe. Unknown legacy sends remain inspectable without automatic replay.

Use existing WorkManager for durable cheap reconciliation with bounded backoff and network constraints. No model polling, blanket session resumes or permanent foreground retry loops. Cancellation before dispatch can cancel local admission; cancellation after acceptance is an explicit original-target operation with its own receipt. Stopping playback does not cancel task work; leaving voice does not discard queued recordings.

Task response receipt commits completion independently of TTS generation or playback. Speech failure retains the text result and exposes retry speech only. It cannot resend the transcript. Playback is session-attributed, nonoverlapping and stale-aware; new recording may interrupt playback without invalidating delivery.

## Migration and release gates

Import the legacy pending WAV/preferences and alpha109 preserved recordings into source-bound journal entries. Migration is repeatable and verified by ID/content hash; retain old files until the new durable record and file are verified. Never migrate unknown ownership into the selected environment. Preserve original accepted and unknown receipts.

Required integration journeys use actual Android SQLite/files and process/service restart boundaries, plus the real isolated gateway: new capture while an older turn is unknown; TTS failure after accepted/completed work; lost admission acknowledgement; killed client before/after seal; gateway restart before/after dispatch; navigation and profile changes during capture; same ID with different payload rejected; cancellation; paired-voice provenance. Assert original request identities and no duplicate dispatch, rather than just counts of unit tests. Qualify the current native audio path and microphone hardware separately from synthetic HTTP fixtures.

Keep the compatible current APK and rollback closure available. Do not label an archive-only fix as the replacement, silently migrate pairings, or install an unqualified transport rewrite on the phone.

# Voice coordinator

The coordinator is a conversational control plane. Codex sessions remain the execution owners. Acknowledging or speaking a report never resumes an agent.

## Evidence and retrieval

CoordinatorMemory indexes public initiating requests, public answers/results and native-head evidence in the existing SQLite store. FTS retrieves candidates; the coordinator makes the semantic routing decision. Native read verifies the chosen target before a mutation. Initial voice context reads stored metadata, not histories. Source identities, observation time, pending questions and outgoing receipts remain explicit. Raw reasoning and command output are excluded.

Existing SessionCatchup is the material outcome journal and presented/unseen authority. A separate listening cursor begins at activation; historical unread work remains available to deliberate catch-up but does not enter an automatic speech backlog. Coalesce material results by source, preserve pending questions, expire obsolete queued audio, and acknowledge only exact completed spoken items. No model or worker activation is allowed in collection/scheduling.

## Federation

Each paired environment is a distinct authority. The central coordinator needs explicitly device-delegated access to the user's other paired environments. Store such credentials encrypted and scoped to that human device; never log/export them. Every reference and operation carries environment + thread identity. Reconcile unknown delivery using its original receipt; never resend on another environment. Remote outages do not block local speech or overwrite state.

## Dialogue and audio

Preserve immutable recording admission, cancellation tombstones and idempotent outgoing IDs. Deliver stable final-response sentences while the model continues, never speak raw thinking or speculative action success. One native account audio transport owns output; live WebRTC playback starts at first audio instead of waiting for the complete recording. Barge-in silences output before microphone capture. Keep a bounded idle timeout, pause/mute and explicit stop-listening controls. No API billing/model changes.

Measure recording-stop, transcription, context retrieval, model start/first public response, stable sentence, first audio and actual playback boundaries. Emulator events are not physical acoustic evidence.

## Qualification

Verify public-evidence routing with misleading/renamed titles, same thread IDs on different environments, source outage, queue/steer, clarification, exact unknown receipts, interleaved outcomes, unanswered questions, coalescing, restart and heard semantics. Native journeys cover repeated volume keys with IME, capture cancellation, barge-in, partial final speech, mute, listener stop and profile changes. Real model/audio qualification uses a dedicated synthetic controller and no business commands.

Live input streams an audio-only copy while the immutable local recording is captured. A session.closed receipt plus the native final transcript becomes device/recording/hash-bound evidence. It admits no task. The sealed WAV is still verified and admitted through VoiceRecordings; only that boundary can submit the complete request. Failure before this proof falls back to the sealed recording. The subscription runtime rejects WebSocket audio with API-key-auth-required, so that evaluated path is not enabled.

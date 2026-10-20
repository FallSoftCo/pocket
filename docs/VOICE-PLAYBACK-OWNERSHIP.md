# Voice playback ownership follow-through

Status: inspected design, not a deployed replacement (2026-10-20).

## Current verified boundaries

Android NativeSpeechRenderer buffers an entire verified clip before PocketSpeechService plays it. PocketVoiceService already has live native output; its pause implementation retains the completed clip and heard position. These paths must converge under one playback owner, rather than adding another player. The experimental VoiceRenderings/NativeSpeechMedia path is disabled in production and must remain disabled until qualified.

Playback is explicitly requested, source-scoped work. Generating an audio clip does not mean the user heard it. Rendering never creates or retries a task turn. Existing uncertain task receipts remain independent of capture and playback availability.

## Required ownership contract

- Capture immutable environment, session, message/passage digest and listening-attempt identity before connection setup. Navigation cannot change their owner.
- Allocate the attempt identity before any asynchronous provider start. Cancellation must be recorded even before a start acknowledgment exists. A delayed acknowledgment must close that same connection and must not begin playback or cancel a newer attempt.
- One physical playback owner controls focus, mute, source selection, live output and cached resume. Reuse existing native live output; do not introduce an independent competing player.
- Pause preserves the heard position and may finish caching the already requested passage. Stop cancels its attempt. Neither queues another passage or initiates task work.
- Cache completion requires the existing passage/transcript/provider-close proof. Cached/generated/heard positions are distinct. Session/profile switches retain source identity and never borrow another profile credential.
- No automatic historical transcript backlog. Existing notification and deliberate listening policies remain explicit, and unrelated queues do not mix into the current source.

## Confirmed gaps before activation

NativeSpeechRenderer currently learns connection identity only after POST start returns. NativeSpeechMedia has the analogous delayed-answer cleanup race. VoiceRenderings cancellation currently requires an existing row; therefore it cannot fence cancellation arriving before admission. Fixing only the UI stop handler or only adding a row tombstone would leave a race across these boundaries.

Qualification must exercise cancellation before admission, during provider startup and after an acknowledgment; delayed completion must not restart output. It must also exercise pause/resume without repeated words, profile navigation, one physical output, and capture while unrelated output is paused. Use isolated transport/media fixtures first and bounded real native audio qualification afterward; do not start a provider merely to inspect state.

This work is separate from the already qualified native JSON transport candidate. Its release must not accidentally include experimental audio activation or unqualified dependencies.

# Native Codex voice investigation — 2026-10-02

Decision: prefer native Codex voice over requiring a separate audio API key for Pocket's default voice experience. The alpha 8 default now uses native account-authenticated audio. The older API adapter is not the default.

## Verified locally

- Installed and latest npm Codex CLI: 0.160.0; realtime_conversation is stable and enabled.
- Experimental app-server schema exposes thread/realtime/start, stop, appendText, appendAudio, appendSpeech, and listVoices.
- A stock app-server process with OPENAI_API_KEY and CODEX_API_KEY removed reported ChatGPT authentication.
- Starting transport type websocket without an API key produced `realtime conversation requires API key auth`.
- Starting transport type webrtc with a browser-generated SDP offer and version v3 succeeded with the same ChatGPT account and no API key. Codex returned answer SDP; browser peer connection reached connected and its data channel reached open.
- Native data channel emitted session.started, session.context.appended, and session.usage.updated. A remote audio track was negotiated.
- appendSpeech accepted a test sentence. The initial connection-only probe did not establish playback. Follow-up October 4 tests established complete transcription, captured response audio, and Android native-player playback.
- Probes used dedicated empty threads and stopped their realtime sessions. They did not send commands to existing user tasks.

## Pocket integration

1. Keep ChatGPT authentication on the paired host. Pocket sends its WebRTC offer through its authenticated Pocket backend; the host calls thread/realtime/start and forwards answer SDP and lifecycle events.
2. Phone audio uses WebRTC. Do not spawn or automate the terminal UI and do not put account credentials on the phone.
3. Attach native voice to Pocket's dedicated controller thread, retaining the pocket_control dynamic tool for list/create/select/reply/steer/queue/interrupt/approval operations.
4. Keep volume-button interaction and microphone capture under Pocket control. Native voice has automatic turn-taking; local mute alone does not establish a strict manual commit boundary. Qualify buffered audio delivery after the stop button or supported protocol controls before shipping.
5. Stop the native session on exit. Verify reconnect, interruption, delayed task results, audio focus, background behavior and real Pixel volume buttons before calling this ready.
6. Detect unsupported CLI/account capabilities and explain them aloud. Do not silently switch to paid API audio.

## Billing evidence and limits

Official pricing says desktop Voice uses the existing Codex budget at $0.05/minute, with task-model tokens billed separately. Credit billing is 1.25 credits/minute and explicitly includes additional credits spent by Plus/Pro users. Native CLI integration successfully used ChatGPT authentication, making this a credible route to account-budget audio. The exact debit of a promotional grant by this CLI transport was not measured. Do not claim a verified grant deduction or assume desktop rates guarantee identical CLI billing without further evidence.

Sources:
- https://learn.chatgpt.com/docs/pricing
- https://learn.chatgpt.com/docs/features/voice
- https://learn.chatgpt.com/docs/app-server
- Installed 0.160.0 experimental JSON schema and isolated runtime probes.

## October 4 implementation and qualification

Pocket now isolates native audio in an ephemeral read-only thread, interrupts any backend handoff from that thread, combines transcription segments from the completed recording, and commits the complete input exactly once to its persistent Codex coordinator. It renders coordinator text through the native voice connection and buffers it before native Android playback. This preserves manual send, pause, resume, and replay without separately billed audio API calls. Native start requires ChatGPT authentication explicitly.

Emulator qualification covered first launch, native response playback, foreground volume-button replay/pause/resume, saved-recording recovery, list preview display and renaming, and conversation-level entry passing the correct session ID. Live host listing returned recent-message previews for all 70 sessions. Pixel wireless ADB was restored on October 4 using the supplied dynamic port. Native speech played on the physical Pixel and its Accessibility service delivered physical volume-button events. The blocking stop-read defect observed during that check was replaced with nonblocking capture. Coordinator launch/start and stop, persistent history, pinned differentiation, volume controls and secondary keyboard entry were checked on the emulator. Lock-screen keys and Bluetooth routing are not claimed as verified.

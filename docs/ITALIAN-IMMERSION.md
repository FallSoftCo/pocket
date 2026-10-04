# Optional Italian immersion

NextComp keeps its normal session, mic, keyboard, tab, question and notification controls. Italian is a display and speech layer over the work: a dedicated, isolated Codex helper translates visible messages, public reasoning summaries and action descriptions. Original task input and command payloads never change. Tap the original-text control to reveal the source; copy/audit use the source.

The mode is **off by default per paired device and local/workstation profile**. Enabling it on the owner's Pixel does not enable it for another device. `PocketImmersion.restore()` must run after initialization, pairing, activating either profile, disconnecting, or changing the stored credential. Its caches and pending batches are cleared on each such transition. `PocketImmersion.accept()` consumes only authenticated device-specific updates. A translation is shown only when its SHA-256 source version matches the currently visible original.

## Runtime and economy

`ImmersionWorker` uses the existing stock Codex app-server. It checks `account/read` for ChatGPT authentication and refuses API-key authentication. No OpenAI API key, third-party translation service or separate paid translation endpoint is used. This still consumes Codex plan usage; it is not free or unlimited.

One ephemeral read-only helper session translates batches of up to 24 texts / 16,000 characters. Sources coalesce by stable identity, unchanged text hits a bounded durable cache, and automatic runs start at most once per ten seconds. Only public display text is submitted, after normal stream coalescing. The helper rotates after twenty turns to bound conversation context. Failed or malformed translations keep the original visible and do not create an automatic retry loop. Tools, writes and delegation are disallowed; tool requests are declined. The helper's thread must be excluded from the work list, activity board and notifications.

The source text is untrusted quoted JSON. Translation instructions preserve questions, uncertainty, numbers, names, links, Markdown and code. This is a useful prompt constraint, not a proof that every model translation is perfect. Critical command actions and question choices retain their original payloads.

## Voice

`PocketImmersion.spoken(id, text)` offers text to the same helper and waits at most eight seconds for a version-matching Italian rendering. It falls back to the original on delay, failure, mode or profile change. Feed its result to the existing native Codex realtime speech transport; do not add an audio API call. Apply at `PocketVoiceService.speak`, which covers coordinator replies and queued updates, keeping action execution attached to the original coordinator response.

Ordinary notification playback uses installed offline Android voices. When immersion is enabled and an Italian voice is installed, it translates the notification through the same Codex worker and selects that Italian voice. If the voice or translation is unavailable, it speaks the original in English. Original queue text is retained; a separate profile/source cache stores the spoken rendering, with completion and caption progress tracking its actual chunk count. Four focused tests cover shorter/longer translations, pause cursors, stale callbacks and profile isolation. This does not claim native-quality notification audio; full voice mode continues using the existing Codex audio transport.

## Ozzz study

Inspected `realtime-immersion-product-prompt.ts` and `docs/language-intent-vision-plan.md`. Relevant transferable patterns are practice-language output with a native-language reveal layer, replay and eyes-free controls, and retaining the software's primary surface rather than adding a lesson/chat shell. NextComp adopts those interaction principles, not Ozzz's paid realtime billing pipeline or a separate teaching screen.

## Official protocol reference

[Codex app-server](https://learn.chatgpt.com/docs/app-server) documents thread/start, turn/start, ephemeral threads and sandbox policy. Runtime uses the repository's existing app-server transport and must be qualified against the installed stock CLI.

## Qualification

Automated tests cover disabled zero-usage behavior, delta batching, content-version cache hits, read-only configuration, API-key refusal, stale-result rejection, per-device persistence isolation and raw-reasoning exclusions. Release qualification must also check actual stock Codex translation completion, source reveal, Italian pronunciation, profile switches during an in-flight batch, question action payloads unchanged, and no hidden helper cards/notifications. Do not claim those live qualifications solely from mocked tests.

A bounded live test on stock Codex CLI 0.160.0 completed successfully on 2026-10-04: advertised `gpt-6-luna`, low effort, ChatGPT authentication, empty temporary working directory, two synthetic message/public-summary sources, 5,583 ms for the batch. Italian retained the inline `notes.md` path and number 3. The receipt is [`qualification/italian-immersion-stock-codex.json`](qualification/italian-immersion-stock-codex.json). No real conversation contents were used. This qualifies actual text transport/schema handling; phone pronunciation and notification delivery still require device qualification.

The model is selected from `model/list`: advertised `gpt-6-luna`, another advertised Luna, or the advertised default. It never requests a model absent from that installed account's catalogue. The device preference key includes both the local/workstation profile and its paired `deviceId`, so a new pairing defaults off.

# NextComp UX quality audit

This is the continuing quality goal, not a declaration that the product is flawless.
Changes need reproduction, regression coverage, appropriate native verification,
and separate installed-device evidence. A successful installation alone does not
prove an interaction works. Preserve drafts, pairing, retained history, queued
commands and environment identity throughout.

| Flow | Acceptance evidence required | Current gap |
| --- | --- | --- |
| Message chronology | Interleaved stream, delayed snapshot, overlapping older pages, reconnect and cached reopen retain canonical order and newer text | Alpha50 installed; physical interaction pending. Backend live-cache update ordering fix deployed; physical interaction pending |
| Freshness and loading | Current list/notification update appears in open chat with visible loading/error state; no full-history startup block | Recheck installed behavior across reconnect and environments |
| History and reading | Upward scrolling loads automatically, retained local pages remain visible, message and pixel anchor survive updates | Unit coverage exists; native end-to-end revalidation pending |
| Session navigation | One tap opens intended session and replies route there; relevant recency without refresh-driven reorder or scroll jumps | Cross-environment and Important-filter audit pending |
| Work state | Working, waiting for input, queued, interrupted and failed states distinguish actual runtime evidence | Audit pending |
| One-handed voice/text | Direct mic capture, physical keys with IME, preserved drafts, source-bound stop/send, clear errors and reachable controls | Physical regression audit pending |
| Notifications and speech | Correct source, rename/read state, session-aware playback, actionable questions, no stale duplicate pinned output | Audit pending |
| Immersion/accessibility | Inline reading-direction transitions without duplicates, legible normal/large text, reduced-motion behavior | Audit pending |

## Verified progress

- Alpha50/code68 installed in place on the Pixel. 271 Android tests and lint
  pass. Canonical overlapping-page positions repaired without rolling back
  newer live text or dropping loaded local history.
- Additional backend defect reproduced with a synthetic interleaved stream:
  deleting/reinserting updated items moved earlier messages after later tools.
  Live release now replaces values without deleting their ordered key. Eight
  targeted backend tests pass on OZZZ-OS, including byte-accounting validation.
- Existing backend process predates the terminal-fork candidate now in this
  checkout. Do not restart that checkout merely to load the ordering fix: stage
  a narrow release against the actual deployed source and preserve active work.

## Operating rule

Stored public reports do not wake workers. This UX audit does not authorize
blanket continuation, periodic model polling, replaying unknown deliveries or
restarting unrelated services. Track remaining gaps here and in UX-REQUESTS.json;
completion requires direct evidence for each acceptance flow above.

## Narrow backend delivery — 2026-10-11

Release d2ae39d is based on e713b61 with only timeline ordering and its test changed.
423 tests pass on OZZZ-OS. Workstation service now loads the isolated release;
shared private data remains in the original location. Before reload there were
zero sending/queued messages, processing voice turns or creating sessions.
Authenticated status is connected afterward, all grouped outgoing/voice/start
receipt counts are unchanged, and original Codex daemon PID2213121 is alive.
Pixel installed alpha50/code68 verified again. Live phone visual ordering remains
unverified. Terminal-fork source and provider registration remain unactivated.

- Further refresh-race reproduction and repair: a newer answer arriving during
  history fetch formerly survived at the front of the seeded cache, ahead of
  earlier user/tool rows. Release 6c7ba69 now rebuilds canonical snapshot order
  while retaining the newer values and live-only tail. 424 narrow-release tests
  pass on OZZZ; connected health, unchanged receipt counts and preserved Codex
  daemon verified after scoped reload. Available emulator 5587 identifies as
  another workload, so no shared emulator input/install was performed.

## Native emulator qualification

OZZZ-OS owned nextcomp_env_qa emulator5620 passed the extended native
TranscriptRefreshNativeTest in17.733s. It verifies visible updating feedback,
retained older history/cache/draft, no repeated-preview polling, chronological
repair and preservation/display of newer text arriving during a history request.
The fixture deliberately holds a refresh response until loading feedback is
observed. Android permissions were pregranted only in the disposable emulator.
This is the nonshrunk qualification variant, not proof of optimized-phone frame
pacing, physical volume buttons, real provider transport or physical ordering.

## Outgoing action controls

Found and repaired a mismatch: request busy state used the environment ID,
while Retry/Edit/Remove/Save checked only the local/remote boolean. All these
controls now use the same current-environment lookup. Native OZZZ emulator
qualification passes in18.655s: another environment does not disable Retry;
matching pending request disables Retry/Edit/Remove; clearing it restores Retry.
The existing chronology/freshness/cache/draft scenario also passed in this run.
Android unit suite and lint pass. Candidate only: phone remains alpha50 and this
change must be delivered in the next compatible update. This test checks logical
busy state and button semantics, not real transport mutation success.

## Alpha51 delivery

Actual source26d4328 built in a new isolated OZZZ worktree. Debug unit tests,
debug lint, optimized phone-compatible build, public-signing release build and
release lint pass. Pixel upgrade returned Success; installed alpha51/code69
verified. Both checksummed signing variants are available through authenticated
private update metadata from version68; no backend restart or provider action.
Physical control interaction remains distinct from installed-version evidence.

## Rapid submission admission

The send flag was formerly set inside a scheduled coroutine, allowing two
rapid calls in the same UI event cycle to create distinct outgoing IDs. Candidate
now reserves sending synchronously. Native OZZZ emulator scenario passed in
18.641s: two same-cycle calls yield one synthetic backend request. Existing
chronology, freshness, retained history/draft and pending-action checks passed
in that same run. Android tests and lint pass. No provider action performed;
phone remains alpha51 and this additional change is not yet delivered.

## Keyboard and back navigation

Extended owned OZZZ native scenario passed in22.127s. Visible Keyboard entry
opens the editor; the redundant Keyboard control disappears while typing;
editing persists the draft; Android Back with the editor/IME open returns to
the session list; reopening restores the editor draft; physical Enter submits
exact unchanged text and clears the acknowledged draft. Synthetic backend
observed exactly the additional intended request. Earlier ordering, freshness,
retained history, pending controls and rapid-admission checks also passed.
This is Android Back dispatch, not an independently qualified physical swipe
on the Fold. No live provider actions or handset input were performed.

## Foreground recording with IME

Owned OZZZ native scenario passed in30.115s. Volume-up dispatch starts actual
Android AudioRecord while editing; Listening, initialized timer and visible
Recording feedback confirmed; captured destination remains the current thread.
Volume-down stops, silent emulator input yields visible capture error without
a pending turn; draft remains. Touch retry starts immediately and one-tap stop
works again. Changed mic accessibility to retain action labels while errors are
state descriptions, rather than replacing the action with an error message.
Updated fixture returns explicit503 without crashing; verified alive after run.
Build/lint pass. Candidate only; physical handset volume keys, non-silent capture,
transcription/send and native audio provider remain separate unverified evidence.

## Session taps and environment isolation

Owned native EnvironmentIsolationNativeTest passed in9.523s against three
synthetic loopback hosts. Card preview taps opened second-thread then same-thread
with correct respective transcript. Settings environment selection switched to
OZZZ; identical thread ID displayed only its destination history; separate drafts
retained. Phone-local socket notification appeared without changing selected
remote conversation. Queued A-origin reply while B selected reached only A, with
exact captured request ID/thread; unknown-delivery outbox entry was not sent or
erased. Switching back restored A history. First selector incorrectly omitted
the Idle prefix; changed selector to include actual card text, not app behavior.
No live credentials, provider, real FCM or physical handset navigation qualified
by this synthetic nonshrunk emulator run.

## Retained-note origin

Keep-latest-reply previously captured thread ID but chose the environment only
when its coroutine ran. A same-cycle environment switch could save on the wrong
host sharing that ID. Candidate now captures destination and note body at tap;
response/error updates require that original environment and thread still be
selected. Extended three-host native test passed in10.064s: save then immediate
switch writes only System76, and the OZZZ view remains clean. Existing card taps,
drafts, notification selection and background-reply isolation also passed.
Build/lint pass; alpha52 will include this candidate with rapid-send admission
and microphone action semantics. Physical-device delivery remains pending.

## Alpha52 delivery

Actual-commit isolated OZZZ build e9d8627 passed271 debug unit tests, both
variant builds and lints. Pixel in-place install succeeded; code70/alpha52
independently verified. Bundles rapid-send admission, mic action/error semantics
and note-save origin isolation. Both private signing variants checksum-verified
and authenticated update metadata qualified from code69. No backend/Codex
restart. Physical interactions and Important-filter passage retention remain
open; emulator checks do not establish real audio transcription or push transport.

## Environment-scoped viewport candidate

Found six remaining conversation viewport/effect keys using local-versus-remote
instead of environment identity. Two remote hosts with the same thread ID could
reuse LazyListState or retain pagination/disposal effect ownership across a
switch. Commit89fe629 keys these by environment ID. Existing backend-bound
read-position cache remains unchanged. Candidate is not installed; native
scroll-and-filter passage retention and cross-host viewport checks remain open.

Actual-commit OZZZ worktree nextcomp-viewport-qa completed debug unit tests,
APK build and lint successfully in1m4s. No runtime restart, pairing change,
worker activation or phone installation occurred for this candidate.

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

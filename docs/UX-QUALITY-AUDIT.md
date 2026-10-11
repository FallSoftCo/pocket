# NextComp UX quality audit

This is the continuing quality goal, not a declaration that the product is flawless.
Changes need reproduction, regression coverage, appropriate native verification,
and separate installed-device evidence. A successful installation alone does not
prove an interaction works. Preserve drafts, pairing, retained history, queued
commands and environment identity throughout.

| Flow | Acceptance evidence required | Current gap |
| --- | --- | --- |
| Message chronology | Interleaved stream, delayed snapshot, overlapping older pages, reconnect and cached reopen retain canonical order and newer text | Alpha50 installed; physical interaction pending. Backend live-cache update ordering defect repaired in candidate, not deployed |
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
  Candidate now replaces values without deleting their ordered key. Eight
  targeted backend tests pass on OZZZ-OS, including byte-accounting validation.
- Existing backend process predates the terminal-fork candidate now in this
  checkout. Do not restart that checkout merely to load the ordering fix: stage
  a narrow release against the actual deployed source and preserve active work.

## Operating rule

Stored public reports do not wake workers. This UX audit does not authorize
blanket continuation, periodic model polling, replaying unknown deliveries or
restarting unrelated services. Track remaining gaps here and in UX-REQUESTS.json;
completion requires direct evidence for each acceptance flow above.

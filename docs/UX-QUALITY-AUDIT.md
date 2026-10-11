# NextComp UX quality audit

This is the continuing quality goal, not a declaration that the product is flawless.
Changes need reproduction, regression coverage, appropriate native verification,
and separate installed-device evidence. A successful installation alone does not
prove an interaction works. Preserve drafts, pairing, retained history, queued
commands and environment identity throughout.

| Flow | Acceptance evidence required | Current gap |
| --- | --- | --- |
| Message chronology | Interleaved stream, delayed snapshot, overlapping older pages, reconnect and cached reopen retain canonical order and newer text | Alpha54 installed; overlap and late-input native/unit checks pass. Exact reported physical causal pair remains uncaptured |
| Freshness and loading | Current list/notification update appears in open chat with visible loading/error state; no full-history startup block | Recheck installed behavior across reconnect and environments |
| History and reading | Upward scrolling loads automatically, retained local pages remain visible, message and pixel anchor survive updates | Native retained-anchor and short-page pacing checks pass; alpha55 installed, physical smoothness unmeasured |
| Session navigation | One tap opens intended session and replies route there; relevant recency without refresh-driven reorder or scroll jumps | Synthetic cross-environment routing and Important-filter anchor checks pass; physical navigation/viewport restore pending |
| Work state | Working, waiting for input, queued, interrupted and failed states distinguish actual runtime evidence | Native Failed/Stopped/Ready/Needs-you checks pass; physical status evidence pending |
| One-handed voice/text | Direct mic capture, physical keys with IME, preserved drafts, source-bound stop/send, clear errors and reachable controls | Native IME, volume-key/touch capture and draft checks pass; physical hardware/IME regression pending |
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

## Important-filter native passage check

Actual candidate31ceb40 passed owned OZZZ software emulator test in8.798s.
Thirty synthetic conversation replies alternate final answers and progress;
three thumb-direction swipes reach older work. Toggling Important on/off
retains a complete visible final passage within24 screen pixels of its original
position. This proves a native retained-row flow, not every removed-row/orphan
case or physical Fold performance. Qualification is nonshrunk. No live session,
provider or phone state touched. Candidate remains uninstalled.

Three-environment native regression also passed candidate31ceb40 in10.332s:
correct card navigation/history, scoped drafts, phone notification isolation,
captured reply and note origin, unknown delivery retained without replay.
This remains a routing/history test, not direct proof of viewport restoration
across hosts. Owned fixture and emulator stopped after terminal test receipts.

## Conversation state distinction

Candidate9a37013 separates Failed from deliberate Stopped and labels completed
idle work Ready rather than implying required input. Failed state uses the
attention color, while pending questions retain Needs you. Actual-commit OZZZ
unit tests/lint/qualification builds pass in41s. Native test passes in14.945s:
failed/interrupted/completed turn transitions and actual pending-question state
render their distinct labels; older-passage filter retention also passes.
This is synthetic runtime evidence, not real automatic recovery qualification.
Stale conflicting catalog/runtime evidence and physical handset behavior remain
open. Candidate is not installed; phone remainsalpha52. Owned emulator and
fixture stopped after the terminal receipt.

## Alpha53 delivery

Source99896dd built in fresh OZZZ worktree nextcomp-alpha53. All271 unit tests
pass; debug and release builds/lints pass. Both retained-signature APKs are
hash-verified and authenticated private update metadata fromcode70 matches71.
Pixel in-place install returned Success and package manager confirmsalpha53/71.
Bundles environment-scoped viewport/effect ownership and distinct Failed,
Stopped, Ready and Needs you labels. No backend restart, pairing reset or
worker activation. Installation is not real-device interaction proof; direct
cross-host viewport restoration and physical accessibility remain open.

## Reachable refresh failure

History-read failures formerly placed ErrorBanner at transcript end, invisible
while reading older work. Candidate3144b2b removes that scrollable error item
and anchors the same error/reload surface above bottom controls; Latest remains
hidden during the error to avoid overlapping actions. No duplicate error area.
Native extensionf62883b passes in36.107s with30 additional synthetic rows and
reading gestures establishing the transcript end is offscreen: actual503 retains
local rows, failure/reload are visible, retry succeeds and typing survives.
Unit tests/lint/qualification build pass; provider and phone interactions are
not qualified. Candidate uninstalled, phone remainsalpha53. Owned fixture and
emulator stopped after the final receipt.

## Permanent history-read rejection

Automatic edge loading had capped exponential backoff, but retried unchanged
credentials/cursor errors indefinitely. Candidate7830400 keeps network,
408/429/5xx eligible and stops automatic page/requeued refresh attempts for
permanent rejection or malformed data. Explicit reload and successful reads
restore eligibility. Coalesced permanent failure also clears refreshPending,
preventing a stuck Updating messages indicator. Unit/lint/QA builds pass36s;
native regression passes38.424s:401 after two coalesced refresh requests yields
one read and no queued retry, updating stops, explicit reload succeeds, draft
and local history survive503. No live revocation/worker activity or phone
installation. Owned fixture/emulator stopped after terminal receipt. Physical
outage and prolonged edge-pagination behavior remain separate audit cases.

## User-reported scrolling and chronology — open

User reports fast/jagged older-page loading and input after its answering reply.
Candidateafdb920 waits for settled gestures before automatic page admission and
before applying an older-page response; two layout frames precede the next page.
Candidate36f7bb9 rechecks gesture/follow state immediately before pending live
scroll. These are not yet native-qualified: combined reading test still sees
latest passage39 on screen after gestures; atomic synthetic hierarchy saved on
OZZZ /tmp/nextcomp-reading-failure-hierarchy.xml. Both gesture directions failed;
do not count harness changes as a successful product fix. Earlier combined test
also failed Recording feedback visibility, retained as separate voice regression.

New-input event previously did not request canonical order. Candidateebf026f
now schedules one coalesced read only for a newly observed selected-thread user
row; does not sort all users ahead of commentary or change other environments.
Isolated native test atdcd0f84 passes7.285s: answer then user event reconciles to
canonical user/answer order, newer during-read text survives, duplicate known
input does not poll. Unit/lint builds pass at appsourceebf026f35s. Current server
copy of this root thread had its latest user before the response; this does not
prove phone order. Exact affected-phone pair remains needed for root-cause
confirmation. No phone delivery occurred: alpha53 installed. Alpha54 debug
builddadf24f passed but delivery held when these new regressions arrived.
Owned emulator/fixture stopped after collecting terminal receipts and hierarchy.

## Viewport diagnosis and isolated history check

At c868586, opt-in native viewport tracing showed endward gestures at the
transcript end never moved the list. Corrected upward-history gestures moved
index37 toward28 with follow disabled; no return-to-latest was observed. The
isolated readingAnchorSurvivesDeferredOlderPage check passed13.732seconds,
including deferred prepend and retained passage within24pixels. This qualifies
that synthetic anchor flow, not physical smoothness or all automatic pagination.
The earlier combined assertion expected pre-fetch streamed text to override an
authoritative snapshot; concurrent updates are covered separately by the passing
late-input test. Running both methods in one process still exposes shared fixture
and singleton isolation; use fresh fixture/process for each pending qualification.
Full keyboard/recording/freshness regression is being re-run. Phone remainsalpha53.

Fresh-process full TranscriptRefreshNativeTest passed38.954seconds on current
candidate: capture status was visible with IME open, physical-key events and touch
recording transitions preserved drafts, silent capture errors stayed visible, and
read freshness/error/retry checks passed. This resolves the earlier synthetic
recording-visibility regression for this run; physical handset remains unqualified.

Alpha54/code72 sourcee50c858 built both signed variants on OZZZ-OS;273unit
tests per variant and both lints pass. Same-signed phone installation succeeded;
package manager confirms72/alpha54. Private updater published both hash-verified
variants, authenticated71-to72 metadata checked. No data clear/backend restart
or worker wakeup. Physical scrolling/perceived smoothness remains unverified.
Owned emulator5620 and fixture19998 stopped. Full UX goal remains open.

## Automatic short-page pacing

Native before/after reproduction found the alpha54 loader initiated3older
reads after one upward gesture at a short-page edge. Candidate3cf2859 consumes
one successful older page per reading gesture (including a fresh gesture made
during an existing read), retains bounded failed-read retries, and needs no tap
to load. Same native check passes14.899seconds:1read after first gesture,2total
after next. Control fails11.187seconds with3reads. Full native regression and
build/lint are pending; this candidate is not installed. Alpha54 remainslive.

Candidate0f5fb7f records scroll-in-progress demand rather than pointer dragging
only, so native accessibility scroll actions can request another page. Touch
then ACTION_SCROLL_BACKWARD test passes14.503seconds with1then2reads. Full
regression earlier failed on screen-wide reading coordinates with IME open;
a3858a6 targets the actual transcript bounds and is being re-run. No phone
upgrade beyondalpha54 has occurred.

Alpha55/code73 sourceef7dadd installed in place; package manager independently
confirms73/alpha55. Both signed builds/lints and273unit tests per variant pass.
Private72-to73 update metadata matches both APK hashes. Native before/after
short-page reproduction and touch/accessibility checks pass; full bounded native
freshness/keyboard/recording regression passes40.403seconds. No backend restart,
data clear, model calls or worker wakeups. Exact physical chronology report and
perceived scrolling/frame pacing remain open. Owned QA processes stopped.

## Partial-page chronology boundary

Candidateae1eee7 carries older-page cursor boundary identity into the response.
Client places older rows before that proven retained row, keeping existing turn
headings at their earlier position and preserving newer text/local pages. No
role-based user-first sorting. Native comparison reproduces the wrong order
(control fails6.881seconds); candidate passes6.952seconds with live output
during read preserved.12targeted backend tests pass. Broader Node run initially
lacked dependencies; isolated OZZZ npm ci and rerun in progress. Not deployed;
legacy compatibility boundary hints and physical reported pair remain open.

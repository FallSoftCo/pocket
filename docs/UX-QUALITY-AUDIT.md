# NextComp UX quality audit

This is the continuing quality goal, not a declaration that the product is flawless.
Changes need reproduction, regression coverage, appropriate native verification,
and separate installed-device evidence. A successful installation alone does not
prove an interaction works. Preserve drafts, pairing, retained history, queued
commands and environment identity throughout.

| Flow | Acceptance evidence required | Current gap |
| --- | --- | --- |
| Message chronology | Interleaved stream, delayed snapshot, overlapping older pages, reconnect and cached reopen retain canonical order and newer text | Alpha58 installed; partial-page and outside-recent-page delayed-input native checks pass; real stock successor read verified. Cross-turn/reconnect breadth and exact physical pair remain open |
| Freshness and loading | Current list/notification update appears in open chat with visible loading/error state; no full-history startup block | Alpha69/code87 installed. Native in-flight-preview/cache-counter checks pass; overlapping same-row delayed snapshot regression passes in a new emulator process. Recent handset stale-content report remains unverified in its exact selected thread; physical arrival and cross-environment reconnect remain open |
| History and reading | Upward scrolling loads automatically, retained local pages remain visible, message and pixel anchor survive updates | Alpha69 installed with bounded persistent local history; distinct-process native pixel/position checks pass. Retained-anchor/short-page pacing and compatibility item/turn boundaries have native coverage. Physical smoothness and large-history eviction/gap behavior remain unmeasured |
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

Narrow backend4a81b84 loaded from actual6c7ba69 base after zero sending replies
and zero unfinished stored voice turns. Phone outsideNextComp; current recording
monitor empty. BackendPID1372872; external Codex daemon2213121 preserved. Live
root history read proves new cursor boundary equals next older-page insertBefore
and names a retained row. Remaining chronology gap: when late input is absent
from bounded recent page, repair currently waits for its older page. Investigate
targeted bounded canonical reconciliation rather than user-first sorting or
unbounded history. Alpha56 signed client delivery in progress.

Alpha56/code74 source2fd538f installed in place; package-manager confirmation
recorded. Both signed builds/lints and275unit tests per variant pass. Narrow
system76 backend4a81b84 passes425tests; broader source453tests pass. Native
partial older-page comparison reproduces and repairs chronology, preserving live
text/local history; full native regression passes41.919seconds. Private update
metadata matches both APK hashes. Other environment services unchanged. Further
work: targeted canonical read when new input lies outside recent bounded page;
legacy hints; actual physical case. No claim of complete chronology/world-classUX.

## In-flight freshness and targeted chronology — alpha57/58

Alpha57/code75 installed after reproducing a lost preview during a scheduled
recent read. Old control fails19.691s; candidate passes9.449s. It retains one
follow-up read, cached messages and drafts without unchanged-preview polling.
See qualification/transcript-refresh-race-20261011.json.

Alpha58/code76 installed in place. An exact-item-anchor read places delayed
input against its proven loaded successor even outside the recent page.
Alpha57 control fails21.753s with input below reply; extended candidate passes
15.205s, covering new live text, draft/local history, navigation, warm reopening,
read failure and explicit recovery without polling. One serial lane stays scoped
to conversation/environment. Early/recent local anchors share the bounded wire
budget; no role-based sorting/model calls. Compatibility paging uses stable
item/turn boundaries when new output arrives.

277 unit tests per signed variant and both builds/lints pass on OZZZ-OS. Full
native freshness/keyboard/voice regression43.571s; in-flight preview case9.469s.
Narrow backend6b7d307 (base4a81b84) passes431tests; full source459tests. Backend
PID1598556 and original daemon2213121 preserved; delivery/recovery grouped counts
unchanged. Real user-anchor-to-loaded-reply lookup passes144ms across4bounded
requests; unauthenticated401/invalid-body400. Other environments/web gateways
unchanged. Private updater hashes match both APKs; phone package independently
confirms76/alpha58. Owned fixture/emulator stopped. See
qualification/anchored-history-20261011.json.

Remaining: exact physical causal pair/scrolling/frame pacing; cross-turn ordering
and reconnect breadth; pathological gaps beyond bounded position search; other
environment owning releases/browser parity; notification/audio and full immersion/
accessibility audit. The quality goal is not complete.

Alpha59 candidate: six native conversation-scope/freshness/history/voice checks pass
on OZZZ-OS. Old scheduled reads no longer suppress new-chat refresh; background
session events do not reload foreground history; hidden provider reads pause.
Narrow backend5a1ada9 deployed with433tests and original daemon preserved.
Routine detail excludes accepted receipts but durable lookup remains accepted.
See qualification/conversation-refresh-scope-20261011.json for delivery status.
Physical chronology/frame pacing and wider quality audit remain outstanding.

Alpha59/code77 delivered in place; independent phone package confirmation.
Both private updater variants validated. Physical transcript interaction remains
unobserved; owned emulator/fixture stopped. No pairing/data clearing.

Alpha60 candidate: notifications/catch-up now require a fresh, settled latest
viewport rather than fetch completion. Native older-history/Latest/filter/blackout/
background-resume case passes31.49s; old-behavior control fails11.386s precisely
when an unseen update was consumed by fetching. Four refresh/navigation/provider/
keyboard/voice regressions pass. Compatible signed build and phone delivery status
are recorded in qualification/conversation-read-viewport-20261011.json.
Presentation does not prove comprehension; first-check catch-up semantics retained.

Alpha60/code78 installed in place, independent package verification passes. Both
private signer variants validated; original transport/install observed to success.
No backend restart, model changes, worker activation or pending-action replay.
Physical read/scroll pacing and full goal completion remain unproven.

Alpha61/code79 qualified and privately published: delayed reply errors remain
source/receipt scoped, popup drafts persist per captured environment/session,
close stays reachable, and failure actions wrap for large text. Final native
case33.549s verifies actual2x app font and48dp target; old global-error control
fails9.714s. Final freshness/keyboard/voice regression41.527s; earlier equivalent
read31.639s/switch18.5s pass.277tests each signed variant, both lints/builds pass.
Phone install did not begin: ADB offline, helper lacks TCP/USB and Tailscale peer
ping times out. Last verified phone remains alpha60/code78. Owned emulator/fixture
stopped; backend/daemon/model/worker state unchanged. See [qualification receipt](qualification/source-reply-feedback-20261011.json).

New open source-audit gaps: frontend durable reply intent/receipt validation;
latest-viewport acknowledgements behind focusable popup/dialog; other async action
error lifetimes. No real unknown send was replayed. Quality goal remains active.

Alpha61/code79 delivered after verified phone reconnection, in-place Success and
independent package check. Current phone does not yet include window-focus fix.

Window-focus source485df4e qualified: actual controls popup/rename dialog retain
unseen updates; dismissal resumes presentation; actual IME still presents latest
and retains draft. Native26.274s passes; compatible old-gate control fails14.127s.
Freshness/keyboard/voice40.968s and source-reply/draft/large-font27.161s pass.
Owned emulator/fixture stopped. This is queued for a combined compatible release
to reduce disruptive repeated installs. See [receipt](qualification/window-focus-reading-20261011.json).
Remaining: durable frontend delivery journal and validation, nonfocusable
occlusion, precise material catch-up presentation, and existing full quality matrix.

Alpha62/code80 is independently verified installed. Current alpha63 candidate adds source-bound typed reply journaling and safe receipt validation, identical-retyped-draft preservation, and live-activity history invalidation without inventory polling. Native live-activity case passes12.508s; repeated events cause no new reads. Current authenticated history read49ms; exact user stale viewport remains unobserved because Android system UI was foreground. Existing global footer labels still truncate at2x and remain an open ergonomics gap. Signed release/install and broad native regression are pending.

Alpha63/code81 delivered: one in-place install returned Success, independent package and installed base APK checksum match verified compatible build. Nine pairing/profile/draft preference hashes unchanged. Both authenticated updater signer variants verified. Signed285tests each/lints/builds pass; native history/keyboard/voice41.889s and source/failure/draft31.672s pass. Only owned emulator/fixture stopped; live backend and workers preserved. Exact physical stale viewport still unobserved; quality goal remains active.

Alpha64 layout candidate: reviewed native captures at normal/1.3x/2x revealed clipped mic caption, unknown-quota text and compact Retry caption. Primary capture/keyboard now have whole tiles and vertical44dp icons with wrapping labels; compact capture uses a larger symbol without inner caption. Unknown quota uses dash plus full accessibility state; cached Italian update/capture labels use existing inline transitions. Native28.599s direct capture/IME/draft/volume/settings and15.683s coordinator Italian/IME/source checks pass. Long capture errors now expand inline on tap, final qualification pending. All app compilation continues on OZZZ-OS. Physical large-text/key presses remain unverified.

Alpha65/code83 and alpha66/code84 delivered in place. Alpha65 extends retryable
foreground history recovery beyond the quick retry budget; alpha66 repairs missed
live events whose subsequent catalog activity advances without a different clipped
preview. Final alpha66 signed286 tests per variant and both lints pass; native
missed-event12.952s/cached-history40.215s pass. Actual phone84/APK hash independently
verified;13 critical preference hashes unchanged. Exact user stale viewport still
unidentified; fetching current root history44ms is not phone presentation evidence.

Current native reading audit: Compose bounds prove older-page arrival and warm
reopen retain the chosen reading passage within2px, with local earlier history
and draft retained (9.481s). Short-page native thumb/accessibility case14.519s proves
idle edge does not drain multiple history pages. Reproduced an additional exit bug:
Important -> Surrounding conversation leaves normal history74px displaced (control
5.472s). Padding now exists only in Important; saved-source row identity is applied
after the item provider commits, without guessed32ms sleep. Corrected native case
5.57s and filter geometry/pixels6.031s pass. Complete destination identity gates
saved-source navigation/removal feedback. Alpha67 signing/delivery remains pending.
Three reviewed original native paragraph crops are byte-identical across filtering;
main gallery card nextcomp-reading-filter-pixel-20261011 records exact scope.
The full quality goal remains open: physical chronology/scroll pacing, broader
navigation/discovery/environment flows, unseen/audio/voice hardware semantics,
immersion reading motion and accessibility still need scoped verification.

Alpha67/code85 delivered in place. Final signed286 unit tests each and both lints
pass. Final saved-context5.522s/warm-reopen9.457s native checks pass; delayed input
ordering7.558s also passes. Independent phone package/base APK hash verified;
13 critical pairing/environment/draft preference hashes unchanged. Both private
signer endpoints offer85. Gallery checker6a5b25540353b89d29092f4e52b19958 completed:
2283cards, desktop/mobile no failures, reduced motion and reload pass. Only owned
emulator/fixture stopped. No backend/runtime restarts or agent/model wakeups.
Previous goal turn was progress (verified alpha66 delivery); this turn is progress
(native failure reproduced, exit repair verified and delivered). Goal remains open.
Additional unqualified scope: current recent-history/reading-position cache is
process memory; process-death restoration and retained-history behavior under
cache eviction have not been proven by these warm-reopen tests.

Alpha68/code86 installed in place with private environment-scoped, bounded local
history and reading-position restoration. 290 unit tests in each build, both lints,
and real two-process native paragraph/pixel qualification pass. Phone package and
base APK digest verified; 13 critical preference hashes unchanged. The previous
memory-only cache limitation is superseded for history saved by alpha68.

New stale-conversation report: paired backend status 26ms and this thread recent
read44ms from workstation; two phone cache entries lack8 and6 current server rows.
These observations do not prove handset transport health or which open screen
was stale. Native follow-up bb93712 prevents persisted counters suppressing fresh
live rows and replay protects disk hydration. Native preparation9.909s and cold
restore4.639s pass, including low-counter live output while history is held.
Alpha69 signed qualification is underway; no new release is claimed yet. No
worker wakeups, action replay, backend restart, pairing replacement or data clear.

Alpha69/code87 delivered in place. 290 tests per variant, both lints and signed
builds pass. Both authenticated signer updater endpoints offer87 with matching
SHA. Actual installed package/base APK hash verified;13 critical preference
hashes unchanged. First empty preflight preference read aborted before any
install; independent86 reconciliation then exec-out read and one successful
installation. No user UI commandeered; reported stale thread remains unspecified,
so handset content recovery is not claimed. Only owned emulator and fixture
stopped; source published to reviewable branch. Goal remains open.

Further freshness qualification on unchanged alpha69 runtime: f9ee607 expands the
real two-process native test to replace same-message while its history response is
held. Preparation12.15s and cold-process4.78s pass: overlapping live text remains,
older snapshot text is absent, separately buffered output remains, reading position
is within2px, draft preserved, zero replies. This closes a test coverage gap without
another phone reinstall. Both cases ran on owned OZZZ emulator5620 with synthetic
fixture; physical user-selected conversation is still unverified. Previous goal
turn was progress (verified alpha69 delivery); this turn is progress (expanded
native evidence). Goal remains active; no worker/model wakeups or runtime restart.

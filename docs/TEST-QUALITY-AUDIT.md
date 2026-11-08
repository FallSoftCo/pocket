# Supported-product test quality audit — 2026-11-07

Audited candidate base `77636b61`, vanilla Codex Android/web. The finite CI
closure below follows the test-only commit `d133e2aa`. Rooms, LAX,
Hollywood and automatic goals are retired or disabled. Historical tests and
qualification records do not re-authorize those runtimes. No production source,
release gate, pairing, phone, account or provider was changed by this audit.

The suite is **not merely disjoint unit tests**. It already has useful production
backend/SQLite/socket/HTTP tests and Android instrumentation. Its principal risk
is inconsistent composition and evidence gates: some new browser controls are
checked only against direct function mocks, while native/provider/phone claims
can be inferred from unrelated historical records. A green total is insufficient.

## Risk-based journey map

| Priority and user journey | Existing evidence inspected | Coverage added here | Remaining gap / owner |
| --- | --- | --- | --- |
| P0: reopen current work after replacement, outage or delayed history | `history-server`, `web-native-history`, `conversation-control-recovery`; Android `NativeCanonicalTranscriptNativeTest` / `NativeTranscriptStoreNativeTest` cover canonical order, SQLite restart, obsolete positions | Real `server/index` → native socket → HTTP → production `HistoryReader`/`SessionStore`; empty client cache, held stale response, real replacement broadcast, loss/reconnect, disconnected islands and exact opaque older cursor | Full web app event wiring/layout and current optimized Android interaction after new context controls. UI/native owner; root schedules retained emulator |
| P0: switch hosts without changing destination | `web-environments.integration` uses real federation gateway but synthetic HTTP backends; Android environment journeys are separate | Two actual backend processes with identical native IDs through actual federation, independent stores and a one-source outage | Native and rendered browser source switch during outstanding writes; UI owner |
| P0: answer only the current native request | Execution tests exercise `QuestionActions` directly; execution DOM manufactures `_pocketUI`; `RequestList` tested without actual transport | Actual native request → backend-generated token → paired HTTP → native response; accepted is distinct from resolved; disconnect/ID reuse; real DOM typing and controlled lost HTTP acknowledgement; no replay; secret cleared | Current Android native decision fields, source change and request replacement; real native provider request semantics. Native owner/root |
| P0: uncertain operation must survive restart without duplicate work | Conversation tools use real SQLite in memory but replace `codex.call`; focused HTTP mounts selected routes | Actual backend process/SQLite restart after socket loss during synthetic compact; receipt retained, conflicting ID rejected, send blocked, inspection acknowledgement does not replay; actual pane recovers via reads and refuses switched source | Successful fork/revert boundary plus settings-change effect on installed native runtime. Root/native owner |
| P0: archive or hide only the original durable conversation | Existing `catalog-membership-server` composes real backend/native socket/SQLite and tests temporary residency, promotion, native absence vs outage, external archive and restart; Android archive tests check drafts and no sends | Re-ran existing production test; no duplicate replacement suite | Native runtime residency semantics and current full rendered source/archive workflow. Root/UI owner |
| P1: pending/accepted/unknown typed reply retains original identity | `send-identity-durable`, `outgoing-projection`, server/reconnect tests cover durable identity, revisions and lost transport at different seams | New unknown-control test verifies actual human reply admission is blocked, without starting native work | Full composer send→lost ack→reload→read original receipt across federation remains separate from control-operation coverage. UI/backend owner |
| P1: configuration, tool management, terminal output | Current configuration HTTP and execution HTTP tests are useful; execution route mounted in isolation; config fixture already uses production server | No duplicated happy-path counts added | Actual native version/capability behavior and current Android controls; full web application lifecycle. Root/native/UI owners |
| P1: voice, notifications and local Android execution | Existing instrumented and historical provider/physical records | None; no microphone, model, provider or phone calls | Change-scoped current native/provider/physical evidence, not synthetic replay. Root owns device/resources |

Ownership names above describe surfaces, not permission to restart an agent or
runtime. This agent owns only the new tests/helper, standalone qualifier and audit.

## What the new tests actually compose

`tests/production-continuity.integration.test.mjs` contains four HTTP/native-wire
journeys and one fixture-contract rejection check. It runs in the existing
`npm test` glob without extra packages. `tests/production-controls.dom.mjs`
contains two additional DOM journeys and must be selected explicitly. These are
six composed journeys, not seven native runtime tests.

The backend is the real child `server/index.mjs`, including authentication,
human ingress, pending-request lifecycle, conversation controls, SQLite and native
transport. Federation uses the actual `createWebClient`/`WebEnvironments`.
The only upstream substitution is a disposable native WebSocket authority. It
never delegates to Codex or a real account. HOME, CODEX_HOME, data and socket
are isolated; runtime/provider environment variables are not inherited. Unlisted
RPC methods are refused and fail the fixture audit; startup account/model probes
receive explicit unsupported errors. The only armed control mutation is synthetic
compaction followed by connection loss. No room fixture is started.

Request/response/event schemas were generated offline with installed Codex
0.167.0, merged without editing their validation rules, and retained with source
file digests in `tests/fixtures/native-contract-0167`. The existing locked MCP SDK
validator checks the exercised messages. Unsigned integer formats have explicit
bounds. This validates shape, not real Codex behavior, timeout semantics, account
availability or version compatibility beyond the named contract. Refused startup
probes and JSON-RPC envelopes are not represented as successful native evidence.

DOM uses the production RequestList and ConversationToolsPane with real HTTP;
only DOM APIs/layout and dialogs come from JSDOM. It is not a rendered browser,
full `web/app.mjs` journey, Android UI, or physical-phone test. History event
application is explicit in the test, so the web application's event subscription
wiring is still outside this proof.

## Gates and assertion quality

- `.github/workflows/check.yml` runs `npm test`, public-source/audit checks, Android
  debug units, release assembly and release lint. It does not run instrumentation,
  release units, debug lint, optimized visual review or physical-phone checks.
- `tests/conversation-tools-web.test.mjs:8,27` silently skip when
  `NEXTCOMP_JSDOM_MODULE` is unset; The audited CI did not supply it, and the standalone execution DOM script was
  absent. The CI closure below supplies the resolved pinned dependency and makes
  both required. Outside CI, direct `npm test` still follows the existing optional
  environment behavior; `npm run test:dom` is the fail-closed DOM entrypoint.
- `scripts/release-qualification.mjs` correctly checks actual native log content,
  one expected success summary, immutable APK/source/test fingerprints, required
  cases, review digests and missing/stale/failed receipts. Existing gate tests
  reject exit-zero `FAILURES!!!`, duplicate summaries and changed artifacts.
- The release builder allows partially skipped JUnit suites (`skipped < tests`),
  and the validator does not reject a nonzero `unit.skipped`. That weakens a claim
  that every required unit case ran. It is not a newly demonstrated product bug.
- Release `productionSourceInputs` bind Android source; qualification inputs add
  fixtures and `server/timeline.mjs`, but do not bind the complete backend/web
  composition. A separate checked backend/web receipt is needed for those claims.
- Current native policy covers stock history, retirement, archive, reading and
  voice. It does not yet require new native execution/configuration/conversation
  controls. Kotlin compile cannot fill that gap. Historical room policy text and
  README/CAPABILITIES Losangelex descriptions are stale, not supported behavior.
- Assertions in inspected high-risk tests generally assert user outcomes and
  negative side effects. I did not find a reason to replace the whole suite.
  Direct method stubs and shape-only tests remain appropriate unit tests; label
  them accordingly. No exhaustive per-assertion audit of all 136 test files is
  claimed, and the full mixed historical suite was not launched here.

The initial test-only change left CI/publication behavior untouched; the finite
CI closure below now pins JSDOM and requires the browser DOM checks. The new core
tests enter CI through its existing glob. Root/native owner must select affected native journeys and
bind them to the exact APK before release. Expanding release fingerprints or
rejecting all unit skips changes historical gate behavior and needs a separately
reviewed change; this audit does not silently modify those contracts.

## Failure sensitivity and reproducibility

Run from the repository with an existing JSDOM module and a fresh output directory:

```sh
NEXTCOMP_JSDOM_MODULE=/absolute/path/to/jsdom/lib/api.js \
  node scripts/qualify-production-integration.mjs /disk/new-qualification-directory
```

The qualifier requires all seven normal checks, zero skips/cancellations/TODOs,
exit zero, unchanged backend/web/test input fingerprints, and two failing negative
controls with the expected assertion. It records complete logs and their digests.
It has bounded requests/test budgets plus a 30-second child-process-group limit;
a missing dependency or timeout cannot be reported as a pass.

The controls modify temporary copies, never production files:

1. Remove the SessionStore stale-read fence: the same cold-replacement journey
   fails with `Missing expected exception` because it accepts the obsolete page.
2. Remove request token from the RequestList key: the same DOM journey fails with
   `REUSED_REQUEST_SECRET_MUST_CLEAR`, retaining the old secret after request-ID
   reuse instead of creating an enabled empty current card.

The first negative-control run exposed a refresh race in the **test**: it checked
an already-disabled input before the refresh actually completed. That could clear
the card during disconnect and hide the broken key. The corrected test waits for
the completed refresh before disconnecting; both controls then fail for their
intended regressions. Rejected run01 remains retained. No corresponding production
defect was found in these bounded journeys.

See `qualification/test-quality-integration-20261107.json` for exact commands,
receipt/log hashes, passed existing regression slice and explicit limitations.


## Finite CI closure

`jsdom` is pinned to `26.1.0` as a dev dependency. The lock update adds 35 packages
and changes no existing package versions. A fresh `npm ci` is the supported setup;
CI never refers to a workstation-specific dependency path.

The backend job resolves the installed module and exports both
`NEXTCOMP_JSDOM_MODULE` and `JSDOM_MODULE` before `npm test`, so its two previously
optional conversation DOM cases execute. A separate required step runs:

```sh
npm run test:dom -- dist/ci/dom
```

`scripts/check-dom.mjs` resolves and verifies the installed pinned version, then
requires all three conversation checks without skips, the existing six-journey
execution DOM receipt, and the composed qualifier including its two regression
controls. It does not trust child exit zero alone. It keeps each child log,
summary and digest, records a failure receipt for missing dependencies, and uses
a new artifact directory to prevent stale-log reuse. It runs this finite lane
even if the preceding backend tests fail, provided installation succeeded and
the workflow was not cancelled. Independent DOM checks still run after another
DOM check fails, producing one failed overall lane rather than hiding coverage.

Explicit Bash steps preserve pipeline failure through `tee`; `upload-artifact`
runs with `always()` and retains dependency, backend and DOM logs for seven days.
No native/emulator job, production service, publication gate or release policy is
changed. This closes the synthetic browser CI omission, not the native/provider,
physical-phone, rendered-layout or full `web/app.mjs` gaps in the table above.

## Release173 follow-through and next boundary

The November7 owning release subsequently passed15 required native cases, exact
optimized request/response ordering, reopen and question dismissal, and actual
emulator touch opening Settings/Skills and Context. These are15 cases including
storage/component checks, not15 full user journeys. The original archive
observation failure and optimized preference-setup failure remain retained.
The corrected preference helper verifies isolation after installation and restores
both the main and backup preference files; it does not weaken ambiguity assertions.

Workstation/web and Mac read-only catalogs are live. Both private update channels
offer173; physical handset installation and interaction remain separate evidence.
Mac0.163 mutations and terminal controls are intentionally unavailable pending
method-specific capability and presentation qualification. Unsupported controls
must not be reported as exercised product behavior.

The next bounded integration is a **cold web-page restart with an unknown human
send**, distinct from an unknown conversation-control operation. It uses the
actual app, gateway, backend and outbox with a disposable native-wire substitute.
After one synthetic native dispatch loses acknowledgement, recreate only the owned
page with its original paired storage. Assert receipt reads retain the original
source/message ID, draft and unknown state, with no second reply POST or native
dispatch. The backend stays running; this cannot be called browser-process or
backend-restart proof. Existing backend-restart and pane-reopen tests remain.

Subsequent gaps, in order: rendered configuration writes/conflict refusal;
Android output paging and structured approvals; source-bound file insertion,
directory changes and child-agent navigation. Each needs a composed journey,
not another disconnected unit-count target. Root owns release/phone work;
integration-quality owns the cold-page journey and execution-parity owns the
Mac read-only terminal presentation. Monitoring remains active without recurring
model wake timers or unrelated worker activation.


## Release174 observations and active follow-up

The cold-page unknown-send journey now passes on the actual app, gateway, backend
and SQLite outbox with a schema-validated disposable native wire. One original
POST and one native dispatch survive page recreation, preserving source, pairing,
draft and unknown receipt. A served replay mutant fails the mandatory assertion.
The exact release source was rerun successfully. This is page recreation, not a
browser-process or backend restart; diagnostic screenshot failures are retained
and no layout proof is inferred.

Release174 passes all15 required native cases, including the extended original
parity journey for read-only and absent terminal capabilities. The exact optimized
APK also passed canonical ordering/reopen/question dismissal and a touch-driven
read-only terminal review. Unsupported termination was absent. Mac listing uses
per-request presentation negotiation; old clients receive an upgrade-needed
response before native dispatch, while the existing0.167 legacy behavior remains.
All five required DOM lanes passed. Debug and release unit suites each had477
cases, with two pre-existing skips reported rather than hidden.

The remote-only stream hypothesis was reproduced: an unchanged catalog left
conversation and attention stale until the ten-second fallback. A separate503
status-probe response also stopped retry scheduling. Both are fixed in the live
web175 gateways: source-specific recovery refreshes history and attention, and
transient status failures retain bounded reconnect backoff. Actual full-app /
gateway / two-backend journeys passed normal recovery, one503 recovery, and a
returned401 refusal control without work submissions. This uses synthetic native
transport, not a physical Mac-provider outage.
Android175 has passed the unchanged15-case batch and exact optimized ordering,
reopen, question and read-only-terminal journeys. Default footer controls now fit
one row; large-font wrapping preserves the numeric credit balance. The four
priority session controls appear before verbose account details. Original failed
IME/menu preconditions remain retained: actual System UI ANR pixels and window
state dated the obstruction before both attempts; one explicit Wait action
cleared it, with no assertion or product-code change before the successful run.
A subsequent runner preflight retains the current hierarchy and refuses existing
Android error dialogs before test setup. It neither dismisses them nor reclassifies
a product failure during a test. The actual read-only capture was separately
verified after the successful batch; the CLI admission test does not constitute
physical-phone proof.

Monitoring remains active. The configuration audit below is complete without
changing real native settings or starting model work. Publication is not complete
TUI parity.


## Rendered configuration follow-up

The full web application now has a composed configuration save/conflict journey
through the real gateway and two production backends, with generated-schema
synthetic0.167 native transport. It observes one acknowledged remote change,
one409 conflict refusal before another native dispatch, original-source/draft
preservation, and no write from a click whose settings response arrives after
changing environments. The exact held response must finish and a subsequent
remote catalog must render before assertions; no fixed sleep establishes success.
Removing only the served post-read source guard produces the expected extra write
and fails the same assertion. No product defect was found.

`tests/configuration.browser.mjs` exports `qualifyConfiguration({context,
outputDir,negativeControl})` for the existing registered browser context and a
fresh disk output directory. It owns only its synthetic local tabs/backends. This
rendered qualifier is explicit, not part of ordinary `npm test` or CI; do not
claim real native configuration filesystem, Mac0.163 mutation or phone evidence.
[Versioned results](qualification/rendered-native-configuration-20261107.json)
include source hashes, failure sensitivity and retained command-setup failure.

## Android176 follow-up

The existing composed native controls journey now covers delayed approval callbacks
after both environment and thread switches, replacement tokens, exact structured
approval choices, output-page scroll reset, held source changes and unavailable
output. All15 required cases passed. The runner receipt took170.055s including setup; the instrumentation log reports
158.44s. The180s cap applies to the instrumentation subprocess, whose exact wall
time is not recorded separately. Future maintenance should split independent
scenarios at actual workflow boundaries, rather than extend sleeps or weaken
assertions.

Optimized review exposed a harness flaw: failed hierarchy dumps could leave a
previous device XML file available. The helper now captures current stdout only.
Its focused regression rejects stale evidence; the first review remains held and
a fresh review passed on the same APK. Exact optimized output paging was also
visually inspected after scrolling, with two original-source revision-bound reads.
These are emulator/fixture observations, not physical-phone proof.

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

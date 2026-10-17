# NextComp system review — 2026-10-17

Status: architecture assessment and implementation direction; not a claim that all defects are fixed or every line is reviewed.

## Product acceptance

The system is delivered when a person can discover ongoing work, understand its actual state, give an unambiguous instruction, receive its original receipt, follow current results while reading older material, answer a question or stop work, and return after an outage without losing or duplicating an action. This must work across retained environments and native rooms, with responsive one-handed interaction, volume-key voice and optional immersion. A running gateway, green unit suite, compiled APK or accepted FCM send does not establish this outcome.

## Reconciled baseline

Maintained candidate starts at 7a78a1c (alpha111/129). Existing bottom-refresh UI work remains uncommitted and unqualified; it is not the architecture solution. The separate speech-readiness experiment remains undeployed. Existing published129 remains immutable.

The six inspected native gateway modules (index, history, computer-use, hollywood, human-ingress, voice-recordings) are byte-identical between this candidate and native alpha110-r03 closure. The system76 stock closure differs in index/history; its history difference includes inherited fork history rather than proof of the reported freshness fault. Preserve both environments and qualify their actual adapters. Shared computer-use adoption is present in both inspected closures. Do not reinstall or restart production as part of assessment.

source-inventory.json fingerprints492 production, resource, test and operator-script files. An inventory is not a review receipt. The detailed read so far covers app routing/lifecycle, stock state/transcript reconciliation, room feed/catalog/send, network/socket, typed outbox, durable voice journal/worker, native rendering boundaries, gateway wiring/auth/history, Hollywood adapter/projection/push, human admission, browser conversation/send/voice and release qualification. Native workers, Hollywood PG and LAX lifecycle remain the other owner's domain; their accepted contracts and incident receipts were reviewed, not modified.

## Findings and evidence

### 1. No single owner for the complete client conversation lifecycle

Stock: PocketState.kt owns selection, catalog, requests and refresh triggers; PocketTranscript.kt owns history/cache/position/live reconciliation; PocketService.kt owns the socket; MainActivity and screen lifecycle initiate further reads. Rooms: HollywoodScreen.kt owns selection, drafts, commands, feed lifetimes and polling; HollywoodRoomFeed has separate head/older/gap mutexes. Browser has another independent app.mjs state/refresh/outbox implementation.

These are confirmed structures, not proof of one universal stale-history cause. Their consequence is that the same product invariant must be implemented and verified multiple times. The stock transcript has one loading gate for recent/older/gap reads (PocketTranscript251–256), while the room feed explicitly separates them. A shared visual footer cannot fix divergent synchronization semantics.

### 2. Read progress, work state and command delivery have distinct authorities

Recent fixes correctly keep accepted room receipts final when refresh fails (HollywoodScreen183–200), validate scope/identity, and keep work state separate from history freshness. Preserve these. JSONObject-based mutable state and screen-owned booleans still make it easy to accidentally derive a command state from a later unrelated read. Model these domains explicitly; never infer idle from a failed read or unknown send from a stale transcript.

### 3. Synchronization policy is embedded in presentation

HollywoodScreen140–170 schedules conversation/activity every3s, detail3s, attention5s, catalog5/15s and separate gap drains. Its open room therefore has a baseline roughly75 reads/minute (two20/min history lanes,20 detail,12 attention,4 catalog), excluding gap reads, notifications and reconnects. This is arithmetic from source, NOT measured network throughput. Independent lanes prevent one failure blocking another, which is good; lifecycle and cadence ownership should live outside composables. Gateway currently exposes bounded JSON history reads; canonical SSE/batch support is not transparently available through this adapter. A push invalidation and a socket connection do not themselves establish transcript freshness.

### 4. UI thread and rendering isolation are incomplete review obligations

Pocket owns a Main coroutine scope; outgoing typed/room persistence uses SharedPreferences.commit at admission/receipt boundaries (PocketReplies9/24, HollywoodClient.persist). This is a concrete possible blocking path, not proof it dominates continuous typing. VoiceTurnStore enforces IO-only durable SQLite/files and provides a stronger pattern. Existing native input sampling mostly found software-render synchronization; physical phone input latency remains unqualified. Do not blame immersion or rewrite persistence based only on that sample. Profile main-thread stalls, layout, text projection and rendering separately under a realistic loaded conversation and immersion.

### 5. Voice has a sound new durable core but still multiple compatibility paths

VoiceTurnStore separates capture/delivery/cancellation/playback and binds immutable origin. DurableVoiceWork and VoiceRecordings preserve admission identity and reconcile rather than repeat unknown dispatch. These should remain the primary path. PocketVoice still includes legacy service/live connection behavior; browser voice has one pending recording that blocks a new capture and a separate real-time audio replay upload path (web/voice8,21–27). Browser text also has independent pending/reconcile logic. Browser parity is therefore not demonstrated by Android fixes. Do not erase legacy unknown records; import them into a read-only recovery view or explicitly supported reconciliation, without treating them as fresh admissions.

### 6. The test portfolio is stronger on isolated invariants than the complete product

Release qualification pins code/APK/log identities and requires actual instrumentation output; retain that. Alpha111 real QA exercised provider audio and canonical transport separately from synthetic UI fixtures. It did not establish physical-phone performance, hardware/audio usability, all-environment chronology or long-lived mixed-load operation. Earlier fixture routing and pre-draw screenshot failures show why process exit or screenshots alone are insufficient. Current badge check failed; no visibility claim is valid yet. More unit cases cannot close these gaps.

### 7. Runtime/release closure must be part of the product

The stock backend, native gateway, app and web gateway are independently deployed. An app version must not imply all server sources match. Keep a release capability manifest with exact component versions, enabled features, schema/contracts and rollback compatibility. A doctor/read-only status can show capability mismatch without starting a model. Keep auth, room binding and pairing migration out of cosmetic updates.

## Architecture to deliver

Use the existing durable foundations. Do not replace the whole application or move backend authority into the phone.

1. A captured ConversationKey includes installation/environment identity, backend kind, canonical room/thread identity and human/direct audience. Navigation, cache, drafts, commands, voice and notification destinations use it. No asynchronous completion consults mutable global focus to decide its destination.
2. A conversation repository owns each open conversation's synchronization session. Backend adapters preserve native IDs, order, scope and cursors. Recent-head reconciliation, older pagination and gap repair have independent request state and bounded concurrency. Push/socket/lifecycle invalidations coalesce in one place. A latest-head read is not queued behind historical catch-up. Reads never start work.
3. A typed immutable screen snapshot exposes separate content, freshness, execution, attention and command receipts. Canonical sequence/identity determines order and deduplication; clocks and role ordering do not. Historical reads cannot roll back the live head. Unavailable status stays unavailable, with last verified time.
4. Commands go through a source-bound journal with persisted-before-send admission, distinct accepted/queued/unknown/failed/completed semantics and exact receipt lookup. Reuse existing backend canonical receipts. Unknown actions never become retryable merely because a read or connection failed. Move blocking persistence off Main while preserving write-before-network ordering.
5. One shared conversation shell and catalog interaction policy consume these snapshots. Backend capability-specific actions remain explicit. Footer, environment picker, loading/error placement, cards, paging and agent/action blocks are shared behavior, not copied controls. Preserve full readability of idle work and use hue/saturation rather than disabled opacity.
6. Capture, upload/transcription/dispatch and playback remain independent actors. New capture is not gated by an unrelated saved turn. One physical player selects logical per-conversation queues. Preserve origin across navigation, volume-key state, intentional mute, per-turn errors and useful intermediate audio. Use measured readiness/first-audio boundaries before integrating the speech experiment.
7. Immersion is a presentation projection over canonical text and actions. It never mutates history identity, acknowledgement or search content. One viewport reading clock drives draw-only transitions; translation/planning/layout are off the key-entry hot path. Transgrammatic English and Italian need aligned phrase structure without per-word empty geometry. Verify actual populated screens, accessibility and large fonts.
8. Browser and Android implement the same documented behavioral contracts and adapter conformance cases. Do not promise shared implementation across Kotlin/JS; share protocol semantics and recorded journeys. Existing web owner owns browser changes when coordinated.

## Implementation sequence and exits

A. Finish release/source matrix and a small diagnostic record for real freshness: invalidation/head received, recent request start/end, snapshot application, render-visible event identity. No transcript/token logging. Reproduce the stale path on stock and native with existing scoped QA. Then move stock synchronization behind an explicit owner with independent latest/older lanes, retaining current merge/identity algorithms until changed by evidence. Exit: canonical newest message appears while older read is held, reconnect/reopen preserves it once, position and drafts retained, no new task created by reads.

B. Apply that same repository/screen contract to native rooms; keep their already independent head/gap/older behavior. Shared shell follows the state contract, not the reverse. Catalog displays canonical meaningful preview/work recency only; absence of backend projection remains explicit. Exit: switch distinct rooms/direct scopes, return from push, concurrent actions remain inspectable without becoming messages, questions/stop target the original work.

C. Consolidate command persistence and voice lifecycle, remove obsolete visible mic affordances per latest user direction, validate volume-key capture through IME/navigation, and eliminate measured input/render blockers. Exit: interruption/restart at each owned boundary recovers original identity, another recording remains possible, playback is attributable and first-audio latency is measured. Real physical verification remains required where device access permits.

D. Complete immersion and remaining ergonomic polish against the shared shell. Exit: global reading cascade without per-key workload or uncovered permanent monolingual strings; no screenshot-only performance claims.

No framework migration, backend rewrite, broad service restart, historical worker resume or unrelated feature expansion is implied. Changes land as complete vertical journeys and independently reversible component releases, preserving pairings and unknown receipts.

## Verification contract

Keep unit tests for difficult deterministic identity/order/state-machine rules. Use adapter integration against actual supported runtimes and retained QA authority for the receive/receipt boundaries. Use native emulator journeys with real user input, long histories, simultaneous actions, keyboard, immersion and controlled network failures. Measure optimized builds. Use physical phone evidence for physical volume/capture/audio/push/rendering claims; declare unavailable access instead of substituting an emulator. One release gate spans actual intended environment contracts and compatible signed APK, not a number of test cases.

Initial proposed responsiveness objectives: local typing feedback p95 below100ms and no main-thread stalls above100ms in the sampled loaded journey; notification/head invalidation to visible already-committed message p95 below2s on a healthy qualified route. These are engineering targets to baseline, NOT achieved guarantees, and must be revised explicitly if real runtime constraints show a different feasible budget. Separate provider generation, transport and playback from local UI budgets.

## Work not yet complete

No full line-by-line review, native/stock stale-path reproduction, new architectural implementation, physical qualification or new release has occurred in this assessment. The existing source inventory and concrete boundaries prevent treating another small UI patch as completion. Additional source review confirmed captured environment/pairing identity in EnvironmentProfiles and HollywoodNotifications, immutable notification reply snapshots, exact scoped receipt inspection, and recovery dispatch guards against unknown/current-turn mismatch with sparse due checks. Those protections should survive consolidation. Browser reconcilePending currently retransmits after a404 whereas Android preserves uncertainty; the server idempotency/retention contract must be reconciled before changing that behavior. Source-only review does not prove either path safe under every lost acknowledgement. Release validator pins APK/code/policy and optimized artifacts but not a complete live fleet capability matrix. Remaining qualification includes actual stock/native freshness reproductions, browser lost-ack integration and real profile-switch/push paths. Keep these evidence gaps explicit before declaring the implementation settled.

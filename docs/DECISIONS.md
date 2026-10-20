# Architecture decisions

Pocket lets Codex notify its own user about its own work. Notifications lead into a two-way conversation with the original task. It is not an arbitrary-recipient messaging service.

- **Stock runtime:** an MCP notification tool and local bridge connect to Codex's experimental app-server. Workstations use the existing shared Unix socket; Android owns a stock app-server child over stdio because PRoot cannot expose Codex's emulated Unix socket reliably across processes. No fork. Compatibility is explicitly versioned.
- **Same-task replies:** tap sends explicit steer guidance (`turn/steer` while active, `turn/start` while idle); hold queues a future turn. Queue order and edits persist in SQLite, and stopped/failed turns pause pending follow-ups. Uncertain delivery is never automatically resent. Model, effort and collaboration settings apply only to a newly started turn. Exact request IDs route native questions and supported approvals. Codex permissions remain authoritative.
- **Single owner:** pairing identifies trusted devices for one workstation account. The model never chooses recipients. This alpha intentionally has no multi-tenant hosting design.
- **Self-hosted application, Firebase transport:** the owner runs the backend and owns the Firebase project. FCM carries bounded previews; transcripts and attachments remain on the workstation. No FallSoft relay or app-store release is needed. Earlier foreground-service transport was replaced with FCM.
- **Phone-local profile:** the same app can retain workstation and phone pairings. The phone bridge and monitor communicate only over loopback and use a visible foreground service instead of Firebase. A Termux runit/Boot service owns the bridge.
- **Phone control:** a system-bound Accessibility service exposes a small authenticated loopback API. Android Accessibility and a separate Pocket switch form two explicit gates. Screen reads omit passwords. Once the owner enables both gates, phone-only tools are preapproved for uninterrupted multi-step automation; consequential actions still require a direct instruction for that exact action. This capability is optional and local to phone-local Codex.
- **Durable outcomes:** replies, push delivery, and phone-created sessions have persistent state and stable IDs. Queued, accepted, failed, and unknown have different meanings. Ambiguous actions are not blindly repeated. Failed and uncertain reply rows remain recoverable through explicit edit, retry or removal; retry of uncertain delivery requires acknowledgement of duplicate risk. Accepted and in-flight rows are protected from these changes.
- **Contextual coordinator:** global requests match existing work using live session metadata and bounded public history, while direct/explicit selection and queue/steer retain their meanings. Named receipts distinguish delivery from completion. Versioned dynamic tools require a new hidden controller on runtimes that restore old tool definitions; supported history injection preserves every durable coordinator exchange before the pointer changes. Existing workers are left intact.
- **Unseen work:** device-scoped foreground checkpoints, interaction metadata and exact presented-item records distinguish checking, sending guidance and receiving findings. Background fetches and queued/rendered audio do not mark findings seen. Short spoken summaries leave omitted body details unseen; unresolved questions remain actionable after presentation.
- **Session speech:** each active execution profile has separate session queues and saved playback positions, with one physical notification player. Finishing holds unrelated ordinary queues; explicit selection and foreground conversation take priority. Coordinator voice parks notification speech. Profile changes save the previous queues before loading another profile, and foreign-profile push audio cannot borrow current credentials. Fully played notification identities suppress duplicate audio without claiming its entire body was heard.
- **Periodic coordinator reports:** an opt-in device-scoped schedule assembles observed findings without inference and skips empty intervals. Fresh inventory is bounded and partial coverage is disclosed. Stale work receives at most three read-only status checks per tick with a four-hour per-session cooldown; asking a worker for an update remains an explicit user action. Offered report findings are not acknowledged until actually presented. Report notifications use a separate transport envelope so older clients do not unexpectedly speak them.
- **Chronology:** snapshots and streaming updates merge by stable turn/item IDs. Tool activity expands in place; history pages backward; manual scrolling pauses following. Raw reasoning is excluded.
- **Attention:** actionable events can remind with bounded approximate WorkManager timers, dismissal, and snooze. Ordinary completion/update alerts do not repeat. Generic speech is synthesized offline in advance, never from task text.
- **Distribution:** MIT source, sideloadable signed Android alpha, and owner-operated infrastructure. Release signing keys remain private and stable across updates. Public release artifacts never include runtime state or local review captures.

The app-server protocol is version-sensitive. See [official protocol documentation](https://developers.openai.com/codex/app-server/) and the installed CLI's generated schema when adapting it. The implemented target and actual test evidence are in VERIFICATION.md.

## Complementary inline immersion (alpha121 candidate)

The former wave/fade and larger-form space reservation are superseded by the user's complementary flip-flop requirement. Prepared lexical units retain contextual Italian phrase order and paired transgrammatical English. Each phase displays both languages within one normal text flow; changing phase reverses the language of every paired unit instantly. There is no per-frame animation, duplicate translation line, modal, or blank width reservation. Canonical messages remain unchanged. Translation preparation and the display clock are separate from transcript and input state, so a display change does not trigger network reads or rebuild unrelated controls.

Missing or invalid provider pairs stay honest source text, rather than invented translations or claims of complete coverage. Existing links and actions preserve their destinations. Qualification must cover complementary units, retained history, recycled content, scrolling and IME interaction. This describes the alpha121 candidate; publication and physical-phone evidence must be reported separately.

## Confirmed transient turn recovery (alpha37)

Definitively failed stock Codex turns continue in their existing thread. Four quick exponential retries transition to durable, sparse waiting: roughly 30 minutes, one hour, two hours, then a four-hour cap with jitter. There is no attempt cutoff for confirmed transient failures. SQLite preserves due times and dispatch identity across backend restarts. Only due checks inspect runtime health and cached usage limits; waiting does not poll a model. Quota reset times delay recovery, while explicit task usage-stop policies and actual permission changes require user guidance.

Continuation asks the worker to reconcile preserved tool results and external actions before doing more work; it never resends the original task. Permission-revoked failures require an inherited permission audit and runtime connectivity check, without applying saved full permissions over the runtime. Nonfull permissions block recovery. Unknown delivery is reconciled by exact message identity and otherwise held without resend. Stop, archive and superseding user work cancel pending retries. Models stay inherited. Ordinary retry/wait states remain quiet, including catch-up reports; meaningful intermediate answers and actual blockers remain visible. Follow-ups held by this same recovery resume after success; separate user-stop and uncertain-delivery holds stay protected. Reconnect snapshots recover only a new latest failure inside an existing watch boundary.

## Screen-on blackout

The optional Settings and voice-status-row action borrows Landlock's black decor-overlay approach. It leaves the underlying conversation and voice services alive, applies minimum brightness to this window only, and keeps the screen on. Tap or Back removes it. Leaving the activity restores its prior brightness, awake flag, system-bar visibility/behavior and accessibility state. Explicit return also restores prior keyboard focus/visibility. Visual read acknowledgements pause while the overlay hides content; actual speech remains independent. It does not lock the device or prevent Home, deliberate locking or forced backgrounding.

- **Explicit runtime permission changes:** Loaded `thread/resume` responses were observed to retain their prior profile despite supplied overrides. Explicit permission controls use `thread/settings/update` and then verify the inherited sandbox, named profile and approval policy before changing the saved intention. They report subsequent-turn scope and partial failures; opening history and automatic recovery never reapply a saved full profile over a user revocation.
- **Scheduled reports:** Work updates retains periodic check-ins outside ordinary coordinator history, with device-scoped presentation receipts and automatic older-page loading. Scheduling, fetching and notification delivery are not read acknowledgments. Cadence is unchanged; report notifications coalesce quietly and speech remains an explicit user choice.

- **Foreground capture:** microphone and volume-key input use the same recording control; capture state stays visible in the current chat while keyboard drafts survive. Playback media volume remains inline with speech controls and preserves mute. Hardware handling is scoped to the foreground activity; saved uncertain turns are reconciled instead of creating a new request.

Settings and app updates must be visible at the start of the footer menu. Activity entries must not push essential maintenance controls below a collapsed section. Opening Settings dismisses the originating popup.

## Evidence-backed continuous work

A completed turn does not certify a finished task. Owner-reviewed unfinished work can enter a persistent continuation chain bound to its exact latest turn. Workers keep working within a turn and checkpoint continue, completed or a genuine input dependency before ending. Original scope remains separate from later progress. Native task goals, cancellation, input, permissions, runtime/storage health and uncertain delivery gate dispatch. Successful progress resets sparse-backoff budget; failed progress does not. Missing assessment is retained without guessing or model polling.


## Retained local conversation presentation

A bounded private no-backup disk cache complements the recent in-memory cache.
It stores loaded message rows, saved excerpts, pagination cursor and settled row
identity/offset. Environment, endpoint/credential digest and installation identity
bind the cache; a new pairing or cleared preferences cannot borrow an old cache.
Atomic replacement preserves the last complete snapshot after an interrupted
write. An advancing authoritative read still supersedes cached streamed versions;
new output during the read wins reconciliation. Cached pending decisions,
execution settings, command queues and read acknowledgements are excluded.
Loading and freshness stay separate from readable cached text. Older local rows
remain directly in the conversation instead of being hidden behind pagination.
File serialization/parsing runs on IO workers; settled position checkpoints are
coalesced and unchanged positions do not write again. This is a presentation cache,
not proof of current worker state, an outgoing delivery journal or an archive.


## Canonical guidance retains the active execution

Human guidance for a busy LAX agent is a Hollywood-owned control bound to the
original native thread and turn. It creates neither a new room nor a parallel
coordinator. NextComp saves a separate source-bound intent and draft, reads the
original receipt after an unknown acknowledgement, and keeps unresolved
guidance available when execution ends. Guide, ordinary Send and Stop retain
different actions. Human identity, room/request permission and native ownership
are server checked; native message recording is distinct from model consumption
and accepted work. Alpha100 implementation and build are not physical-phone
or general multi-host certification.

### Browser read feedback (2026-10-19)

Browser events invalidate their own projection and scope. Session previews update list entities; other sessions' timeline deltas, aggregate activity and repeated status observations do not reread the selected transcript. Known current rows apply by stable identity/version. Unknown current rows and semantic completion/receipt changes request the independent current-head lane; requests coalesce without concurrent duplicate reads. A quiet periodic reconciliation remains for missed events and older transports, with no worker/model activation.

The reading-edge status observes the entire logical head promise, including its coalesced follow-up, rather than individual HTTP requests. First snapshots show loading immediately; cached reads must last 400 ms before appearing. Once shown, feedback holds at least 500 ms and settles for 160 ms across nearby work. Scope changes isolate feedback; stale completions cannot clear the selected reader. The indicator sits inside the reader frame without changing its dimensions. Errors retain existing messages and surface their actual failure.

## Browser modeled symbol playback (2026-10-19)

The browser reuses the native modeled WebP atlases instead of static-only PNG copies. One shared requestAnimationFrame clock draws only visible connected symbols into fixed-size canvases. It never updates transcript state, DOM text or layout per frame. Page visibility and reduced-motion preferences stop playback; PNGs remain the accessible fallback if motion or a resource is unavailable. Decoded pages use a 32 MiB LRU; compressed source pages use a separate 12 MiB LRU so looping does not repeatedly fetch evicted decoded pages. Pending decodes are single-flight by page, and failed pages do not retry on every animation frame. Removed nodes are unobserved, and no per-symbol timer/listener survives transcript reconciliation.

## Shared browser session entities and read-only history (2026-10-19)

Catalog and paginated history are payload projections of one session entity, not independent client authorities. SessionStore owns metadata, causal read tickets, canonical rows and snapshots. A matching live row patches the cached entity even when closed. Message/interaction previews and changed catalog summaries signal missing content; they are not substituted for complete messages or used to guess chronology. Selected reads have their own lane; at most two changed sessions prewarm in the background with a 5-second per-session bound and a 32-entry queue. Background loading does not mark work read. Cold background loading is enabled only when the server advertises readOnlyTimeline; legacy servers retain cached live rows without background history GETs. Background requests use the dedicated /head endpoint, so a backend rollback returns404 instead of falling through to a runtime attachment. Modern history GET uses the bounded projection reader and never thread/resume. Explicit write/control paths retain attachment and permissions handling. Older in-flight metadata cannot overwrite a newer live observation in either view.

## Grammatical flip units (2026-10-20)

The immersion teacher authors small grammatical phrases as the atomic complementary flip units. Determiners stay with noun modifiers/heads, prepositions with their noun phrase, and negation/clitics/auxiliaries with their verb. Idioms stay intact. Italian order remains canonical for both language forms; ordinary intra-phrase spaces remain spaces, with hyphens reserved for bound grammatical glosses. Existing renderers already preserve each aligned segment as one unit, so this adds no per-frame parsing or animation.

Semantic plan revision v3 invalidates word-oriented teacher caches; old content is regenerated only when offered as visible content, under the existing deduplicated bounded worker. Android and web accept v2 during coordinated rollout. The browser clears retained plans on semantic revision so an open view cannot indefinitely retain the old segmentation. Common authored controls have explicit reviewed groupings; longer legacy authored settings strings still need individual grouping review. Structural validation proves complete aligned coverage, not linguistic correctness or improved learning outcomes.

Grammatical flip implementation (2026-10-20): the teacher returns Italian lexical alignments plus contiguous grammatical group IDs. The server derives each group’s English support from those lexical meanings in Italian order, rather than asking for freely reordered phrase glosses. Determiners/modifiers stay with nouns, prepositions with their phrase and negation/clitics/auxiliaries with the verb. Missing, discontinuous or incomplete groups are rejected; validated phrase segments are cached and rendered atomically. This adds neither a second model pass nor a client-side language parser.

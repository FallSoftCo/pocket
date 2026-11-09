# Local UX measurements

Android and web keep small recent foreground-only timing aggregates in memory.
They have no telemetry endpoint, automatic upload, recording of content, session
or account identifiers, URLs, keystrokes, credentials, audio, or screenshots.
The instrumentation accepts fixed metric/outcome enums and monotonic time only.
There are at most 32 active journeys and 128 completed latency samples per
metric. Export includes count, min/max and nearest-rank p50/p95, not event traces
or journey tokens. Long successful waits remain in the distribution; samples
are not clipped at two minutes. Counters describe this process, capped at one
million. Process exit resets the buffer.

Android exports these aggregates under `ux` in the existing **Report a slowdown**
JSON. The expensive 15-second frame/main-queue/stack capture remains explicitly
user-triggered. The small recent UX buffer is already present when capture
starts. Web Settings provides **Download local responsiveness report**, which
creates a local JSON download with no backend request.

| Metric | Evidence | Population and limits |
| --- | --- | --- |
| `open_first_content_layout` | First selected message/user content intersecting the laid-out viewport | Direct-session foreground selections; excludes empty conversations |
| `open_cached_first_content_layout` | First content arrived before fresh history was established | Subset of first-content samples, not a separate network request |
| `open_fresh_content_layout` | Selected visible content after current history applied | Android crosses composition/frame boundaries; web checks viewport geometry on an animation-frame callback |
| `history_response` | Successful history transport response returned/parsing completed | Includes current/older/background repairs while foreground; does not prove valid rendered history |
| `send_feedback_committed` | Local delivery feedback UI state committed | Android composition or web DOM commit; explicitly not optical presentation |
| `send_admitted_callback` | Original send acknowledgement validated and admitted | Not model first response, work completion, or exactly-once execution |

Superseded selection, cancelled request, failed request, background retirement,
capacity overflow, and unfinished journey counts remain separate from successful
latencies. Pending opens/sends without success are not counted as fast samples.
A failed/retrying open can later produce a real latency; the request-failure
counter preserves its transport failures. Expected capability/archive refusals
can also contribute to the request-failure count; this is not a product-error
incidence rate. No content-length or identity labels
are added. Current coverage excludes coordinator/room opens, voice interaction,
archive controls, new-task creation, and detailed per-operation error causes.

The Android content observer is active only until the selected open reaches its
fresh-content milestone or loses ownership. It uses no global transcript-revision
subscription and does not cancel frame waits on streaming updates. Once complete,
its collection ends; later scrolling adds no observer work. Both clients cancel
in-flight journey tokens on navigation/backgrounding. Visible layout/frame
observation is not proof that a user saw pixels; send feedback is deliberately
reported at the weaker composition/DOM-commit level. An overlay/focus restriction
can prevent a sample rather than fabricate a successful observation.

Tests cover cache/fresh/transport separation, navigation/cancellation, retention,
180-second waits, and the production Android presentation coroutine completing
under continuous emissions and unsubscribing. Native Compose/layout and browser
DOM qualification remain separate from aggregate/coroutine unit tests. Reports
support comparisons of observed interactions; they are not population analytics
or proof of universal responsiveness.

Initial send admission has separate `send_failed`, `send_unknown`, and
`send_cancelled` counters. Android uses an optional, non-persisted diagnostic
callback tied to the original dispatch; storage failure before dispatch or a
validated failed receipt is failed, while a timeout/lost or unknown receipt is
unknown. Unknown means reconciliation is still required, never proof execution
was absent. The web likewise separates local persistence failure from uncertain
admission/receipt refresh. Later recovery and model/task outcomes do not rewrite
that initial observation or replay its callback. Closed journeys ignore duplicate
or late callbacks. Existing command journals, retries, drafts and final `onDone`
callbacks are unchanged; trailing-lambda callers retain their original binding.

Web exports include the export timestamp, current viewport dimensions and device pixel ratio as export-time context only. They are not per-sample layout evidence. Loaded JavaScript build identity is explicitly unavailable; a backend version is not substituted for client identity.

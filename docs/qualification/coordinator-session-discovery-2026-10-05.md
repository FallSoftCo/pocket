# Coordinator discovery and lightweight inspection

## Reported symptom and verified state

The user clarified that the temperature-check conversation appeared once its first response arrived. This is delayed initial discovery, not evidence that the conversation was permanently missing.

Authorized local API probes found both requested sessions in the existing 68-row list and accessible through their normal conversation endpoint:

- The user-reported workstation temperature/fan task: idle; followed; verified work directory.
- The user-reported crew/sprite task: idle; followed; verified work directory.

The local creation ledger records both as successfully started with full permissions. No task was submitted, replied to, interrupted, archived, or cancelled by this qualification. Private message contents were not printed.

## Change

Creation records actual metadata immediately and emits `sessionStarted` with that metadata before waiting for an assistant response. Listing includes bounded state-database pagination and explicit top-level stock Codex source kinds. It merges known created, selected, and followed sessions through metadata-only reads. Real remembered creation metadata remains available if the Codex index is temporarily not ready; it is marked `discoveryPending`. Missing metadata refresh is bounded to eight calls per poll and a 750 ms foreground wait, with a 30-second cache. It never resumes sessions or reads their histories for list discovery. Archive flags and private coordinator/immersion exclusions remain in force.

Voice selection remains explicit and device scoped. External TUI focus is not silently treated as the voice-selected conversation. Android should upsert `sessionStarted.thread` immediately and then refresh its list.

A short read-only workstation question may execute directly using the persistent coordinator's stock Codex tools and return its verified result in coordinator history. Sustained programming, mutations, installations, rendering, and long-running work still use a work session. Per-turn sandbox policy enforces the current full/review preference even if that coordinator was originally created with full access. Review uses a read-only policy for direct coordinator inspection.

## Validation

Regression tests cover pagination/source inclusion, newly watched sessions, hidden/archived exclusion, remembered creation before index readiness, and lightweight results persisted in coordinator history under review sandbox restrictions. No provider audio or new live Codex work was invoked for these tests. Backend deployment and physical phone verification belong to the root release workflow.

Final backend suite: **181 tests passed, 0 failed**. Discovery-specific tests: **4 passed**. Syntax checks and whitespace checks passed. The normal HTTP history integration test now verifies explicit exec/app-server source inclusion rather than depending on the obsolete 70-row list size.

Initial session cards report pending only while the initial outgoing turn is queued/sending; active/error/completed states are preserved. Observed live turn/status events update remembered metadata so a delayed index cannot hold a new session pending until its first assistant response.

## Runtime latency follow-up

After backend restart, the real owner `/api/threads` request exceeded its 10-second deadline repeatedly. Isolated stock Codex metadata-only `thread/list` calls took 139 ms (70 rows/default sources) and 147 ms (100 rows/explicit sources); the full discovery helper with a read-only database proxy returned 300 rows in 651 ms. The live Node process was observed in Linux `D` state at `jbd2_log_wait_commit`, and the database used WAL with FULL synchronous commits. This supports SQLite durable-write amplification as the observed bottleneck, rather than a proven source-filter or provider network problem.

Discovery now batches metadata writes per page, skips unchanged metadata writes, and shares one in-flight refresh. A 1.75-second asynchronous refresh deadline returns remembered actual metadata while a slow stock RPC finishes in the background. Canonical notification title reconciliation now batches its changes in one transaction; individual rename requests retain their original transaction behavior. The regression test verifies concurrent stalled lists share one refresh and still return newly-created remembered metadata without waiting for a first response.

Runtime service restart and real API timing verification are coordinated by root; no session is restarted or cancelled by these helper changes.

A second real timeout exposed another bulk-write path: list-preview hydration invoked `conversationNotes.remember` for every historical card. Removing that list-only side effect (conversation opening and live events still collect notes) restored real API responsiveness. Cold older cards now retain metadata/cached context without loading history; active, recently updated, and followed cards still hydrate live previews. Opening a conversation batches its historical note backfill into one transaction and publishes one complete snapshot.

Final service verification after these changes, with no active voice work: three authenticated real `/api/threads` calls returned in **1886 ms, 1831 ms, and 166 ms**, each with **298 rows** and both reported target sessions present. `/api/status` returned in **5 ms**. The Node process was sleeping in `ep_poll`, not blocked in a journal commit. The first two calls exercised the bounded remembered-metadata path while refresh completed. No new work session, task interruption, or paid audio request was used. Final backend suite: **184 passed, 0 failed**.

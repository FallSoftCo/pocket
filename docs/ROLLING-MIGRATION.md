# Rolling migration of Codex work

Status: rolling-controller design; a narrower [self-service terminal fork candidate](TERMINAL-FORK.md) is implemented and isolated-qualified. No live task has been migrated. The candidate preserves native context with explicit split scopes, but does not claim global ownership fencing or logical-work UI unification.

NextComp should move one logical work item at a time while other work continues. A user keeps the same conversation entry, drafts and catch-up state; the entry shows its current environment and migration status. Native thread IDs remain qualified by environment. A logical work ID maps to a source thread and, if needed, a destination successor. This is additional functionality beyond alpha48 named profiles.

## Native capability and evidence

Official [Codex app-server documentation](https://developers.openai.com/codex/app-server#threads) provides stored-thread read/resume/fork. It rejects an in-progress fork boundary. These interfaces do not establish cross-host live-process migration. Locally inspected generated ThreadResumeParams has threadId/config/cwd overrides but no arbitrary rollout-path or history import parameter. Documentation and installed runtime capabilities can differ; qualify exact source and destination versions.

Existing coordinator dynamic-tool replacement qualification on runtime0.160.1 reconstructs public coordinator conversation with injected items, preserving focus and unknown-delivery annotations, with zero inference turns. It is a narrower mechanism than arbitrary native history portability and was qualified in an isolated local profile, not across these production environments.

Two paths must be distinguished:

- Native continuation: only after an isolated cross-host test proves the complete persisted store, transitive fork/compaction lineage, configuration and runtime compatibility. A JSONL rollout alone is insufficient evidence, especially for paginated history. Do not merge unrelated live SQLite databases or share writable Codex homes between runtimes.
- Reconstructed successor: a new thread receives a provenance-bearing handoff with original requests, constraints, public progress, useful intermediate answers, artifact references and unsettled operations. Keep original history accessible. Explicitly label reconstruction; do not claim preservation of hidden state or native lineage.

## Durable handover

1. Inventory intended work and exact owners. Preserve finished work, pauses, cancellation, usage stops and dependencies. Do not enroll the history inventory for automatic execution.
2. Prepare destination while source remains authoritative: authenticated account, same intended model/permissions, effective instructions/tools, exact repository commit plus dirty/untracked content, artifact checksums, path mapping and reachable external dependencies. Verify destination resource headroom. Authentication configuration is host-private, not part of a portable task payload.
3. Persist migration ID, source/destination identities, ownership generation, manifest hashes, stage and desired outcome. Retries observe this record; no model turns are used to poll progress.
4. Stop admission of new work to the source at the command gateway. Let the current turn and its dispatched tools settle. Check background jobs, children and external receipts independently. A disconnected source or interrupted model does not prove its external actions stopped. Unknown deliveries remain blocked for reconciliation.
5. Snapshot at the verified boundary. Include task scope/stops, history and lineage references, files, accepted/queued/unknown command receipts, read/presented markers, notification provenance and coordination references. Browser leases, live forms, terminal processes and controllers are separate dependencies, not copied ownership.
6. Restore destination without starting work. Verify history/lineage readability, workspace hashes, effective configuration and task/dependency identity. Read-back checks must not generate inference.
7. Atomically transfer authoritative ownership using compare-and-swap generation. Both command admission paths must enforce it; a database lease alone cannot fence a source runtime receiving input elsewhere. If independent source input cannot be excluded, migration remains blocked. Retarget only definitely unsent commands, preserving immutable original receipts and explicit migration provenance. Accepted/unknown commands are never replayed.
8. Resume only eligible unfinished work on the destination, with a durable activation receipt and normal capacity admission. Verify native acceptance, meaningful progress and notification/reply routing. Keep source history available and source execution fenced. Rollback before destination execution restores ownership; after destination effects it requires a new settled reverse handover.

## Rolling policy and UX

Begin with new work routed to the destination and one tool-free disposable cross-host canary. Then qualify completed/idle threads, one safe unfinished task, child ownership and queued inputs before increasing the batch. Long-running jobs can remain on their original host with remote observation, or drain before migration; do not restart jobs to simulate relocation. Device/browser controllers may remain on system76 while model/build work runs on OZZZ.

No periodic coordinator wakeups, blanket continues or successful-turn rearming. Migration stages advance from durable events and cheap bounded checks. Interrupted handovers survive controller restart, destination failure and lost acknowledgements. A target environment outage should retain queued intent and clear source identity in the UI.

## Required qualification before activation

- Exact native history portability for legacy, paginated, compacted and forked records; missing lineage fails without losing source history.
- Source admission fencing, independent worker inputs and split-brain rejection.
- Lost reply and controller restart at every stage, with one migration/activation identity.
- Pending tool/external effect, background job and unknown-delivery settlement without replay.
- Draft, notification action, coordinator references, speech source, read/catch-up and old-history links across the environment mapping.
- Same intended account/model/permissions and changed effective instructions detected.
- Destination unavailable before/after ownership transfer; safe rollback and useful status.

Migration parent owns host provisioning/transport. Pocket Dev owns the logical-work mapping, command-routing integration and Android qualification. Coordinate exact capabilities rather than duplicating backend provisioning. This proposal does not authorize live cutover, source eviction or permission/model changes.

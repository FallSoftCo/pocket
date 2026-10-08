# Work lifecycle and model admission

Decision, 2026-10-08: replace generic model continuation with durable, event-driven work. The lifecycle belongs to a deterministic control plane. Codex is a worker that reasons and uses tools, not the authority that schedules its own next turn. Periodic reports are projections of saved events and results. They never activate work.

This is the replacement contract and a tested admission kernel, **not a deployed cross-client scheduler**. Legacy successful-turn chaining remains suspended. `server/work-admission.mjs` is not wired to live dispatch and does not register, wake or interrupt existing sessions. Passing kernel tests is not qualification of a backup executor or of every native client.

## What failed

The old mechanism accepted a worker's `continue` prose after a successful turn as admission for another model turn. It reset the retry delay, treated non-user dependencies as runnable work, and had no global ownership or compute limit. A checkpoint mentioning a previous artifact could exempt an otherwise unchanged wait from the heuristic. Repeated rereads consumed inference without advancing the requested result.

There was also a second dispatch path: parent agents could send new messages directly to existing workers. A hold on NextComp's recovery row does not intercept that path. An enforcement claim must name the dispatch paths it actually covers.

## Authority and records

An accepted user intention identifies a durable task independently of a chat title. It has one accountable root owner, its latest authorized scope, explicit stops, acceptance conditions, and actual result references. Existing threads and children are associations with that task; inventory refreshes do not create tasks. Reuse the intended task before admitting another owner for the same work.

A bounded work unit has a stable ID, immutable instruction, acceptance-contract reference, task ID, owning thread, revision and state. It describes an actionable step, rather than "continue everything." The owner accepts units; workers can propose plans, results and dependencies but cannot self-admit a successor. Repeating an accepted intention rejoins the same unit. Conflicting reuse of its ID is rejected.

A dependency identifies the exact producer/resource and generation that can unblock the unit. Examples are an archive job's verified completion, a particular owner approval, a changed runtime-health generation, or the user answering an outstanding question. An inbox read, elapsed time, refreshed timestamp, old artifact, status report or generic message does not satisfy a dependency. Multiple transport messages reporting the same generation are one event. Events arriving before subscription are retained.

A dispatch attempt has a persistent request ID, unit/task/thread identity, and eventual native turn ID. Persist admission before sending. Only a verified native acceptance binds the request to a turn. A worker's prose is not a transport receipt. Restart and elapsed time never prove that an uncertain request was not accepted.

## State transitions

| Current state | Evidence or command | Outcome |
| --- | --- | --- |
| Accepted actionable unit | Fresh complete capacity/policy snapshot and atomic claim | Dispatching, with persistent request ID |
| Dispatching | Matching native acceptance | Running |
| Dispatching | Lost acknowledgment or owner crash | Unknown; retains its slot until reconciled |
| Ready / awaiting result | Exact dependency subscription | Waiting; consumes zero model turns |
| Waiting | Matching, previously unconsumed producer generation | Ready once |
| Running | Matching native terminal receipt plus reconciled external effects | Awaiting result / failed / interrupted; releases compute |
| Running | Native terminal receipt with unresolved external effects | Unknown; no successor admission until trusted settlement |
| Awaiting result | Trusted acceptance adapter verifies the contracted result | Unit completed |
| Running / unknown | Explicit cancellation | Stopping; retains the slot until exact native settlement |
| Any stopped/completed unit | Duplicate or late notification | No new dispatch |

The task may remain unfinished while no unit is runnable. That is a dependency wait, not a reason to run another model. Missing checkpoints and unknown scope become a visible assessment requirement; they do not trigger a background assessment model.

## Deterministic work and acceptance

Use a persistent job/process for transfers, checksums, archives, renders, service-health probes and waiting on job completion. The job can continue while the Codex thread is idle. It emits an immutable result receipt. Run inference when interpreting a new result, selecting a next operation, repairing a concrete failure or answering the user requires reasoning.

Acceptance is specific to the operation. A backup unit needs the exact source inventory, destination manifest, complete hash verification and required restore evidence. A successful transfer exit alone does not verify a usable backup. Cleanup requires the matching authorization and verified protection of unique/in-use assets. Research and creative work can require an owner's acceptance; there is no general-purpose deterministic proof that arbitrary prose achieves a user's intention.

A trusted adapter validates receipt source, task/operation/generation, manifest/content hashes and the acceptance-contract version. It records the decision without starting another reviewer model. The admission kernel accepts adapter decisions; its `source` parameter is an integration boundary, **not authentication**. Never expose it as an endpoint where a caller can merely assert `source: job` and manufacture authority.

Completion of a model turn is an observation about compute. It is neither task completion nor evidence that another turn is justified. Completion of an accepted unit allows only an already accepted, dependency-satisfied successor to become eligible; it does not generate an open-ended next plan.

## Recovery is separate

Confirmed transient failures resume the existing attempt/context after capped backoff and runtime/account-health checks. They do not resend the original instruction or app submission. Health checks are deterministic, with unchanged failures backing off without model calls. Policy stops and explicit cancellation win. Unknown delivery requires exact request/turn reconciliation and external-action inspection; it never expires into a resend.

The existing `TurnRecovery` adapter retains those protections while successful-turn chaining is disabled. Migrating it requires binding recovery to the work attempt, persisting the retry schedule in the same workflow, and avoiding two owners retrying one failed turn. The kernel intentionally does not implement a second failure-retry loop.

## Concurrency and usage

There is one admission authority for managed background cognition across projects. Start conservatively with two total model slots and one active owner per task, counting root and child workers together. Interactive user commands get priority; queued background steps wait without occupying a reasoning worker. Slot denial must return durable queue/wait state, not activate a model to complain or poll.

Count unrelated native active threads in capacity observations. A partial, stale, disconnected or failed observation is not proof of spare capacity. A dispatching/unknown/stopping attempt retains its slot even when absent from an inventory snapshot. Unique active-task and active-thread constraints plus transactional claims prevent overlapping managed owners. User-requested additional parallelism is a policy change, not a worker preference.

Native subagent spawning must consume the same task's slots, using supported native concurrency controls where verified. The installed source distinguishes legacy `agents.max_threads` from `multi_agent_v2.max_concurrent_threads_per_session`; a per-session native setting is not a global account cap. Do not silently change settings or assume one key covers both modes. Until all managed spawn paths join the gate, show their activity as unmanaged and do not advertise global enforcement.

Use runtime token-usage deltas, native goal usage where present, wall time, dispatch purpose, producer event and accepted result to explain usage by task and attempt. Cached/uncached/output transport counters are distinct; they do not establish subscription prices. Read existing native goals and preserve explicit usage stops. Do not create goals, invent token budgets, buy credits, enable recharge, or change models to implement this scheduler.

Cap automatic admission as policy; do not use a sliding budget that silently disappears on restart. Suspend units that repeatedly produce no accepted result, and retain a concrete reason. A meaningful artifact can justify additional work only after its contracted acceptance, not because its path appears in checkpoint text.

## Execution boundary and reuse

Reuse the existing Temporal-based task service for durable workflow history, waits, signals, cancellation and operation checkpoints on this installation. Its currently qualified operations are narrow desktop/browser capabilities; it does **not** already schedule arbitrary Codex turns or backups. Extend its versioned contract with a separately qualified model-work adapter and capacity lane, without replacing or restarting active services during design qualification.

The SQLite kernel is a local reference implementation of the admission invariants and useful for single-host qualification. It is not a competing production orchestrator or a horizontally scaled lease service. Multiple consumers can share transactional claims on one local database; different host-local databases do not share capacity. Fleet use belongs to the shared durable workflow authority, with fenced workers and versioned deployment.

Every managed dispatch path must use that authority: NextComp recovery, coordinator delegation, scheduled tasks, parent follow-ups and native child spawning. Direct CLI/VS Code/native/Losangelex calls outside the adapter remain outside enforcement. At startup and reconnect, reconcile the complete native inventory, ongoing jobs and outstanding sends before admitting more work. Never treat every historical idle thread as unfinished authorized work.

Stock Codex remains the execution runtime. Its app-server exposes turn start/steer/interrupt, persisted goals, and lifecycle events. Existing account authentication, model and permissions remain unchanged. Official reference: [Codex App Server](https://learn.chatgpt.com/docs/app-server). Installed protocol/source checks are separate from documentation and release evidence.

## Migration and qualification gates

1. Keep the current containment and saved checkpoints. Audit live intended tasks, consolidate duplicate owners and preserve stops; do not enroll the full history inventory automatically.
2. Register narrowly accepted work units and exact result/dependency contracts. Archive and transfer executors retain their existing job IDs and reconcile before any retry.
3. Run the new authority in observation mode. Explain every proposed dispatch and rejected duplicate; it makes no model calls. Compare it against native inventory and actual owner/job receipts.
4. Integrate one real producer and one Codex dispatch adapter first. Qualify before broad activation: event-before-subscription, repeated and conflicting events, competing owners, service crashes before/after send, lost ACK, late accept after cancellation, uncertain external effects, goal/policy stops, stale inventories, and prolonged outages.
5. Migrate parent/delegation and child-spawn paths to that same gate. Unmanaged paths remain explicitly visible. Activate bounded scheduling only after that boundary is qualified; remove the old successful-turn checkpoint rearm path instead of running both mechanisms.

No new production dispatcher, worker or automatic task grant is installed by this design. The qualified current containment remains active until the owning adapter and real result producers pass the gates above.

## Reference-kernel evidence

`tests/work-admission.test.mjs` exercises the actual SQLite implementation, including two independent database connections, persisted restart reconciliation and 48 simulated waiting hours. It verifies ownership/capacity fencing, event replay, producer-generation deduplication, early result delivery, cancellation with late acceptance, and the separation of native turn completion from accepted work. These are admission invariants, not a real provider/phone/backup end-to-end qualification.

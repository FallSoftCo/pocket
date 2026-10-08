# Audited task continuation

An idle session is not evidence that its task finished. A completed turn can also be a partial report, a handoff or a real request for input. The owner audits current intent and receipts before admitting unfinished work through the owner-only continuation route. Admission binds to the exact latest terminal turn and excludes children, uncertain message delivery, outstanding input, task usage stops and recorded cancellation. It does not replay the original task.

Registered work receives preserved-context instructions and an authenticated checkpoint command. The worker should keep working within its current turn. Before ending, it reports concrete evidence with `scripts/task-checkpoint.mjs --state continue|completed|needsInput --evidence "..."`, from its original session with `CODEX_THREAD_ID`. The helper reads the existing local owner credentials without printing them. This is a privileged local interface, not a new project sandbox.

`continue` means authorized work remains and can proceed independently; `completed` means the requested outcome was achieved; `needsInput` means a genuine user dependency. Exact current-turn receipts are idempotent. Stale, contradictory and cancelled receipts are rejected. A missing checkpoint remains awaiting assessment; it is not silently labeled complete and does not initiate model polling.

SQLite preserves admission, checkpoints, request identity and retry deadlines. Successful progress continues promptly. Confirmed transient failures use bounded quick retries, then sparse exponential backoff capped at four hours with jitter. Health and quota checks precede dispatch. Waiting does not poll a model. Unknown acknowledgement is reconciled by exact accepted-message identity, never blindly resent. Completion, user stop, archive, superseding work and actual policy blockers retain their distinct meanings.

Runtime interruption requires evidence that it was not an explicit stop. A historical interruption alone does not establish that. Likewise, inventory metadata and bounded transcript tails are coverage evidence rather than certification of every historical task. Keep per-session audit findings private; publish only synthetic tests and bounded implementation evidence.

Native task goals are read through `thread/goal/get`. Paused or usage/budget limited goals prevent dispatch; an unavailable policy read delays recovery. Local storage must retain at least 64MiB available headroom. Resource exhaustion is a confirmed transient failure, subject to the same sparse recovery and health checks.

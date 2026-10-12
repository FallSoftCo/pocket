# Saved typed replies and recovery

Stock direct-session typed replies retain a private intent before dispatch, bound to the originating environment, credential owner and thread. Their stable UUID survives process death. Opening/resuming the conversation checks the existing server receipt; it does not submit a new task or poll a model.

A prepared intent has not been dispatched and can finish its original send once. A dispatched intent with an unknown outcome is checked read-only. Missing receipt is not proof of non-delivery. Explicit Retry warns about uncertainty, checks the same receipt first and retains the same identity. Stop removes prepared sends before dispatch. Removing a saved copy does not cancel work already received by the server.

Only a matching known receipt state can acknowledge handoff. Queued/held/sending means handed to the durable server outbox, not completed work. Failed, unknown, malformed or wrong-thread responses preserve the draft and recovery controls. An acknowledgment clears only the original draft generation; edits, including retyping identical words, survive delayed acknowledgments. Changed pairing credentials cannot inspect or replay an old owner's intent.

The journal stores message text and receipt metadata in app-private preferences with Android backups disabled. Accepted entries are pruned after safe draft reconciliation. Voice, coordinator, notification reply and team paths are separate mechanisms; this qualification does not certify them or exactly-once external execution.

# Saved typed replies and recovery

Stock direct-session typed replies retain a private intent before dispatch, bound to the originating environment, credential owner and thread. Their stable UUID survives process death. Opening/resuming the conversation checks the existing server receipt; it does not submit a new task or poll a model.

A prepared intent has not been dispatched and can finish its original send once. A dispatched intent with an unknown outcome is checked read-only. Missing receipt is not proof of non-delivery. Explicit Retry warns about uncertainty, checks the same receipt first and retains the same identity. Stop removes prepared sends before dispatch. Removing a saved copy does not cancel work already received by the server.

Only a matching known receipt state can acknowledge handoff. Queued/held/sending means handed to the durable server outbox, not completed work. Failed, unknown, malformed or wrong-thread responses preserve the draft and recovery controls. An acknowledgment clears only the original draft generation; edits, including retyping identical words, survive delayed acknowledgments. Changed pairing credentials cannot inspect or replay an old owner's intent.

The journal stores message text and receipt metadata in app-private preferences with Android backups disabled. An accepted receipt clears only the original draft generation and releases the composer; it does not prove that conversation history already contains that input. The full local message remains visible as Sent until the exact canonical echo is stored and handed to the conversation. This presentation is separate from canonical message ordering.

Retirement matches the original outgoing ID to the canonical `clientId`, within the original environment, credential owner and thread. Identical text, a matching turn ID, a newer timestamp or an empty outbox is insufficient. A delayed or failed history refresh cannot downgrade an accepted send. Unknown delivery retains its recovery controls even when history contains an apparent match. Accepted local presentation never triggers resubmission or model polling.

Web uses the same separation between durable intent/receipt and local presentation. Accepted evidence in its existing delivered-receipt journal masks stale queued/sending snapshots; fetching history is independent of acknowledging the original submission. If the provider supplies no exact echo identity, the local copy stays honestly labeled until the user chooses Hide local copy. That action removes presentation only, not the accepted request or canonical history. Android offers the same accepted-only dismissal.

Voice, coordinator and notification reply paths are separate mechanisms; this direct-session typed-input behavior does not certify them or exactly-once external execution.

## Canonical Hollywood notification replies

A notification action captures the verified event revision and its original
human, enterprise, room, audience and native attention scope in private app
state. Its Android PendingIntent carries that snapshot identity. A later
notification refresh or resolution cannot retarget that action to the currently
selected environment or discard its typed text merely because the visible
record is being refreshed. An unchanged metadata hint schedules authorization
refresh without replacing the last verified payload with a metadata-only record.

The receiver saves a response intent against that original action. Before any
write, the worker checks the original credential owner, installation/human
binding and currently authorized inbox event. Hollywood validates the exact
native scope, request digest and current question state. A cached action is not
a permission grant; a stale or revoked response can be rejected while its saved
text and original receipt remain inspectable.

Prepared replies may finish their original send. After dispatch may have
started, recovery reads only the original operation receipt. A lost success
acknowledgement is not grounds for another POST or a replacement agent request.
Acceptance of a human answer, native consumption, artifact submission, business
acceptance and native turn completion remain separate facts.

Snapshots and response intents remain in Android's private preferences with app
backup disabled. They include routing/response data, not host service tokens.
Changed pairing ownership refuses use. Retention/pruning of historical action
snapshots remains release hardening; unresolved response receipts must not be
evicted to impose a simple size limit.

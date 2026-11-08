# Requested agent notifications

Status: design recorded; not enabled by central voice151.

A user request such as “tell me when the build passes, but only interrupt me if it fails” creates a durable notification intent bound to the original human device, environment and work identity. It records the requested condition, expiry/cancellation, importance, push/audio choices and the original request receipt. A broad watch is not equivalent to a specific requested alert.

Agents publish attributable evidence through a typed notification capability. They cannot author human replies, grant themselves urgency or manufacture user consent. The capability accepts an intent identifier, stable event identifier, condition/result evidence and bounded content. It returns a persistent receipt; retries read that receipt rather than creating another push. Existing accepted and uncertain sends retain their identities.

The notification decision is deterministic over stored events. Routine tool steps are activity, not separate interruptive pushes. They coalesce into work updates. Requested outcomes, actionable questions and material failures remain distinct. A visible, current conversation normally receives the update inline; attention is not marked consumed until it is actually presented. Push and speech consumption remain independent.

Rich content should include source, the requested condition, a short result, optional bounded preview and direct navigation/reply/decision actions tied to the correct environment. Action labels must describe their consequence. A notification alone cannot approve an operation. The user can inspect, change or cancel their intents without messaging every agent.

Implement on the existing notification journal and delivery queue rather than creating a second delivery authority. Extend the current notification tool boundary with idempotency and principal provenance; do not send agent information through human task admission. Coordinator natural-language interpretation may create an intent only within its active original human command. Background event matching never wakes a model.

Qualification must cover repeat/lost-response delivery, restarts, changed account/profile, same session IDs on different environments, foreground/background transitions, expired/cancelled intents, unchanged blocked work, and independent audio/push choices. No flood of fine-grained updates should be needed to discover one requested result.

## Delivery foundation candidate

The existing operator notification route now has a staged optional Idempotency-Key contract. Identical retries return the original receipt; conflicting content is refused. Notification, receipt and durable push queue commit in one SQLite transaction, so restart recovery cannot duplicate the push. Concurrent independent handlers are covered. The route reads source metadata without attaching, resuming or downloading the conversation. Actual HTTP integration checks authorization, concurrent/lost-response retries and one queue entry. This foundation is not yet deployed and does not claim requested-intent matching or new agent authority.

The current shared MCP adapter caches each service’s tool list and owns a persistent notification-tool subprocess. New tool schemas require an owning per-service refresh/release; restarting the whole shared adapter would unnecessarily interrupt unrelated services. Existing notify_user clients remain unchanged until that integration is qualified. Original agent authorship and original human request provenance must remain explicit.

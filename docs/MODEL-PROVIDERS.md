# Model providers

The Android and web 195 candidate adds **Model providers** to settings and
**Provider** to New task. These controls use the selected environment's native
Codex configuration. They do not replace Codex execution with a proxy.

Choose a configured provider, or add a Responses API-compatible endpoint with
its own API key. DeepSeek is prefilled with `https://api.deepseek.com`; its
documented `deepseek-flash` model and `high` effort are suggested for new tasks.
The model ID remains editable. Availability and billing belong to the provider.
See [DeepSeek's Codex integration](https://api-docs.deepseek.com/quick_start/agent_integrations/codex/).
This feature does not make ChatGPT credits usable with another provider.

An API key is held only in the transient setup form, then stored by Codex in
its private user configuration using its supported provider bearer-token field.
It is not stored in NextComp preferences, local storage, receipt tables or API
responses. Alternatively, supply the name of an existing environment variable
already available to the Codex daemon. An environment-variable name does not
install a credential or change the daemon's environment. Setup never starts
inference to test credentials. There is no automatic fallback to another provider.

Provider selection applies to **new sessions**. Existing sessions, including
queued replies, keep their original provider. Configuration IDs already in use
cannot be overwritten in this setup form; choose a new ID for a different
endpoint or credential configuration. Cross-provider continuation/fork is not
implemented in this candidate. Provider selection, model and optional effort
are persisted with a new-task request before dispatch and survive backend
restart. Reopening an original request does not consult a changed host default.

The implementation uses `modelProvider/list`, `config/read` and an optimistic
version-checked `config/batchWrite`, followed by ordinary `thread/start` with
`modelProvider`/`model`. Native Codex 0.171.0 was inspected and exercised against
an isolated local Responses endpoint. This made one synthetic request, verified
endpoint/model/credential routing, private configuration mode and unchanged
OpenAI default. A separate owner-authorized workstation check reused the existing DeepSeek key and completed one real native `deepseek-flash` turn in 3.758 seconds. This establishes that host/key/model path, not arbitrary provider compatibility.

In this runtime, `model/list` can retain the old provider registry after setup
and custom gateways can receive the built-in OpenAI model catalog. Consequently
NextComp does not advertise those models as belonging to an external provider.
New tasks use an explicit external model ID. Existing external sessions do not
offer an invented OpenAI model picker. Provider setup is unavailable on runtime
versions outside the qualified profile; opening the controls never upgrades or
restarts a daemon.

## Cross-provider continuation follow-up

Codex 0.171 exposes `thread/fork` with `modelProvider`, `model` and a completed
`lastTurnId`. An isolated native proof preserved source user/assistant messages,
but a further probe also forwarded opaque source-provider reasoning to the new
provider. Raw native fork therefore cannot be assumed portable across providers.

The proposed handoff creates a fresh native destination session from a durable
work checkpoint, accompanied by a reference export of public conversation and
tool results. The checkpoint records task intent, constraints, decisions,
completed artifacts, next steps, blockers and unresolved external effects.
Provider-specific reasoning is excluded. Source/destination linkage and original
operation receipts survive restart, and uncertain actions are never replayed.
This workflow is requested but is not part of the delivered provider selector.

The handoff should be one durable operation in the existing conversation-actions
workflow, rather than a second memory service:

1. At a completed turn boundary, pin the source environment, thread and last turn.
   An active source must reach that boundary, or be explicitly interrupted; a
   provider change must not silently leave two workers doing the same task.
2. Save an immutable checkpoint and a reference transcript with stable turn/item
   IDs. Preserve the original logs. The checkpoint includes the current objective,
   latest scope corrections, completed work, decisions, paths/commits, verification,
   pending questions, deliberate stops and uncertain external effects. A summary
   is an index into this evidence, not a replacement for it.
3. Create a fresh native thread with the explicitly selected provider and model,
   in the same environment and workspace. Its initial context includes the
   checkpoint, a bounded relevant transcript excerpt, and paths to the remaining
   public messages/tool results. Preserve author roles and source labels: an old
   tool result or agent comment must not become a new human instruction. Do not
   forward encrypted reasoning, dangling
   tool-call records, credentials or provider session/cache identifiers.
4. Persist the linked destination before continuing. Lost acknowledgements are
   reconciled using the original operation receipt; never create another thread
   or replay an external action simply because a reply was lost. Unknown effects
   stay unresolved until their actual state can be checked.
5. Show the destination as a continuation with a link to its source and checkpoint
   date. Both transcripts remain inspectable. Further provider changes make new
   versioned checkpoints, so returning to a previous provider does not resurrect
   its stale working state.

Checkpoint creation should happen at an explicit handoff, with later updates at
useful work boundaries; no background model loop is needed. The first version
must also work when the old provider has no allowance: public-history export and
existing checkpoints remain readable without starting a source-provider turn.
The destination can read further evidence on demand. Persisting a checkpoint
cannot promise that every model will understand or obey it identically.

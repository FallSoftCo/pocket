# Model providers

Android and web 195 add **Model providers** to settings and
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
endpoint or credential configuration. Cross-provider continuation is available in Android and web196 through
**Context & files → Continue with another provider**, extending the195 provider selector. Provider selection, model and optional effort
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
New tasks use an explicit external model ID. Version 201 adds a picker populated by the selected provider’s authenticated GET /models metadata endpoint; it does not use the native OpenAI catalog for external providers. Existing external sessions do not
offer an invented OpenAI model picker. Provider setup is unavailable on runtime
versions outside the qualified profile; opening the controls never upgrades or
restarts a daemon.

## Cross-provider continuation architecture

Codex 0.171 exposes `thread/fork` with `modelProvider`, `model` and a completed
`lastTurnId`. An isolated native proof preserved source user/assistant messages,
but a further probe also forwarded opaque source-provider reasoning to the new
provider. Raw native fork therefore cannot be assumed portable across providers.

The implemented handoff creates a fresh native destination session from a durable
work checkpoint, accompanied by a reference export of public conversation and
tool results. The checkpoint records the latest request and update, source settings, instruction
evidence, prior handoffs and unresolved operation identities. The linked public
history supplies the evidence for decisions, artifacts, next steps and blockers;
this version does not invent a model-generated semantic summary.
Provider-specific reasoning is excluded. Source/destination linkage and original
operation receipts survive restart, and uncertain actions are never replayed.
196 implements this workflow. Backend and web delivery are live; Android delivery
is recorded separately in the qualification receipt below.

The handoff should be one durable operation in the existing conversation-actions
workflow, rather than a second memory service:

1. At a completed turn boundary, pin the source environment, thread and last turn.
   An active source must reach that boundary, or be explicitly interrupted; a
   provider change must not silently leave two workers doing the same task.
2. Save an immutable checkpoint and a reference transcript with stable turn/item
   IDs. Preserve the original logs. The checkpoint and linked history preserve evidence for the current objective,
   scope corrections, work, decisions, paths/commits, verification, questions,
   child identities, stops and uncertain effects. Existing child work is
   referenced, not recreated. The checkpoint indexes this evidence; the new
   agent must read and reconcile it rather than assume every item is current.
3. Create a fresh native thread with the explicitly selected provider and model,
   in the same environment and workspace, preserving the authorized permissions
   and task constraints. Its initial context includes the
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

## Continue with — 196

Android and web use the existing conversation-operation receipt path. Choose
a provider and explicit model, optionally add handoff instructions, then confirm
**Save handoff & continue**. The source must be settled with no waiting or unknown
input. The original draft and transcript remain available; the continuation links
back through conversation controls. This does not interrupt a running source.

Preparation writes a private, immutable checkpoint and chronological public-history
JSONL on the same environment. It includes stable native turn/item identities,
latest user request and assistant update, native instruction evidence with original
roles, prior handoff references and an explicit reconciliation policy. It is an
evidence index, not a model-generated claim that work is complete. The destination
reads additional context from those files. Reasoning items and provider caches are
excluded; binary attachments are not copied. Common credential patterns are
redacted, but arbitrary secrets in free-form historical text cannot be reliably
identified. This operation shares the retained work context with the chosen
provider under its billing and data handling.

The new native session preserves workspace roots, source base instructions,
approval policy, supported sandbox/profile settings, disabled plugins and
collaboration mode. Destination permissions and provider/model are verified before
input admission. Developer instruction history remains labelled evidence; current
runtime instructions govern. No source-provider inference is required. Native
remote execution environments and app-owned dynamic tools are explicitly rejected
until their handoff contracts are qualified. The source transcript is unchanged.

Creation is never retried after an uncertain native acknowledgement. Once the
destination is acknowledged, its identity is persisted before input admission.
The linked receipt and the single ordinary outbox message commit together with
the original paired-human provenance. Existing outgoing reconciliation handles
sending; fetching or replaying the operation receipt cannot create another thread
or message. A successful receipt means saved and queued, not completed.

A second continuation from the same source boundary is refused in favor of the
existing receipt. Source changes during preparation abort before input admission.
External TUI clients remain independent writers: this is not a distributed lock
on arbitrary user actions, and an old source can still be deliberately resumed.

Export bounds are 3,000 turns / 64 MiB of public history and 512 MiB of scanned
instruction lineage. Exceeding them fails before creating a destination rather
than silently omitting earlier context. Checkpoints use private file permissions
and are not public gallery assets. No new model polling or memory service exists.

[196 delivery and qualification](qualification/provider-continuation196.json) distinguishes live web/backend support from Android publication and physical installation.

## Advertised model picker — 201

Android and web request model names only for the selected configured external
provider, through the existing authenticated `/api/model-providers?modelsFor=ID`
route. The backend resolves the endpoint and credentials from the same native
Codex configuration; credentials never reach the client. HTTPS or local-loopback
HTTP, no redirects, a four-second deadline and a 256 KiB/256-model response bound
apply. Concurrent reads share one request, successful results cache for one minute,
and failures briefly cache without inventing supported models. No inference,
provider reconfiguration, session creation or automatic fallback runs on discovery.

The picker shows provider-advertised IDs and keeps manual entry available. Late
responses cannot cross the selected provider/environment or replace manual input.
An unavailable catalog leaves manual entry usable. Existing sessions and saved
creation/continuation receipts retain their original explicit model selection.

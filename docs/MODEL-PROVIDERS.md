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
`lastTurnId`. An isolated native proof preserved source user/assistant messages
and sent the next turn to another local provider without altering the original.
The proposed UX combines that native fork with a durable work checkpoint and
source/destination link. Provider-specific reasoning/tool records and unknown
external effects require explicit qualification and reconciliation. This handoff
workflow is requested but is not part of the delivered provider selector.

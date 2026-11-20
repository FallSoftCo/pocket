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
OpenAI default. No DeepSeek inference or billing has been exercised.

In this runtime, `model/list` can retain the old provider registry after setup
and custom gateways can receive the built-in OpenAI model catalog. Consequently
NextComp does not advertise those models as belonging to an external provider.
New tasks use an explicit external model ID. Existing external sessions do not
offer an invented OpenAI model picker. Provider setup is unavailable on runtime
versions outside the qualified profile; opening the controls never upgrades or
restarts a daemon.

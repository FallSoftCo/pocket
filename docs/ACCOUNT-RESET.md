# Scheduled earned usage reset

The account-reset job uses the signed-in Codex app-server's documented
`account/rateLimitResetCredit/consume` endpoint. It redeems one specifically
authorized earned benefit; it never purchases credits, enables reload, changes
models, restarts tasks, or uses API-key billing.

Private state in `data/account-reset.json` binds the account, exact credit ID,
expiry, threshold and one durable UUID idempotency key. Default operational
threshold:98% weekly usage. A minute timer performs only account metadata reads
until near exhaustion. It avoids redemption just before the normal weekly reset,
refuses unsupported/missing benefits and spending-control stops, and preserves
existing-credit continuation when a reset is unavailable.

Run the owning systemd oneshot under `flock` using the same lock for any manual
invocation. Enable a persistent calendar timer so process/host restarts don't lose
the schedule. The script saves dispatch intent and fsyncs the state before the
mutation. Uncertain delivery reconciles the exact same idempotent attempt, never
selects another benefit or creates a replacement key. Once acknowledged, only
verification reads can follow. Backoff caps at one hour; there is no model polling.

This is distinct from an ordinary scheduled weekly reset. A title like "Full
reset" does not establish referral provenance: use the backend description and
reset type. A successful `reset` outcome means eligible rate-limit windows were
reset; verify the resulting account snapshot rather than inventing a new deadline.

[Official app-server account endpoints](https://learn.chatgpt.com/docs/app-server#api-overview-1).

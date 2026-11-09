# Standing banked-reset policy (version 2)

The owner's standing authorization is separate from the earlier one-off receipt.
Use a new private `data/account-reset-policy.json`; retain the original
`data/account-reset.json` byte-for-byte. The runner does not silently upgrade it.
`standingAccountReset(legacy, {accountId, authorizationReference})` constructs a
new policy and embeds the original receipt. Bind it to the actual authorized
account privately. Never check account IDs, credit IDs, or private receipts into
source. Existing `redeeming` attempts carry their exact credit/key into version
2; acknowledged attempts enter recovery observation without another consume.
Malformed/unknown legacy states need explicit reconciliation. Deliberate stops
remain stopped even when constructing the new policy.

Version 2 uses `schemaVersion: 2`, `mode: "standing"`, `enabled: true`, a bound
`accountId`, an `authorizationReference`, `thresholdPercent: 99`, `status`, an
optional `attempt`, and retained `receipts`. `armed` means the policy continues
checking even when no usable banked reset exists. Its trigger is **any valid
Codex primary or secondary allowance window at 99–100% used**, not merely the
weekly window. A usable window has finite usage in 0–100%, a positive finite
window duration, and a positive finite reset timestamp or explicit null.
A nonempty bucket map must contain `codex`; unrelated buckets never authorize
fallback. Account mismatch, ambiguous usage, spending-control uncertainty/caps,
unsupported limit stops, disabled policy, or paused/cancelled state block use.
There is no imminent-normal-reset deferral in the standing policy.

Available `codexRateLimits` credits are selected by earliest unexpired expiry;
explicit null expiry sorts last. Expired, unsupported and already completed
credits are excluded. Before consumption the writer atomically replaces and
fsyncs the file and parent directory with the exact credit ID and a newly minted
UUID key. Save failure stops dispatch. Unknown delivery remains `redeeming` and
reconciles only that same pair—even if the credit disappears, expires, or usage
falls—after account and cap checks. No reset purchase, automatic recharge, API
billing switch, model invocation, or task resumption is performed.

`reset` and `alreadyRedeemed` enter `awaitingUsageDrop`: only reads follow until
all validated Codex windows are observed below 99%. This prevents a stale
99–100% snapshot from draining another banked credit. `noCredit` retains a
terminal receipt with the observed eligible ID set and permits a later attempt
only with a genuinely new eligible benefit ID. Other credits already present in
that unchanged inventory cannot be drained on subsequent ticks. Exclusions are
accumulated across all retained `noCredit` receipts, so later negative outcomes
and quota changes cannot re-admit those old IDs.
`nothingToReset` retains its receipt and usage-window fingerprint; unchanged
windows cannot mint another key. Changed usage or window identity can re-admit a
later attempt. These negative outcomes do not permanently disarm the policy.

The existing single owning timer and `flock` remain the scheduler. Deployment
must point `POCKET_RESET_STATE` at the new policy, pin the existing
`CODEX_SOCKET`, and clear `POCKET_CODEX_COMMAND` (the runner rejects command mode).
Use the same lock for state changes/manual invocation. Do not create a second
Mac timer or native process. The runner emits only status/usage/reason summaries.
Tests use synthetic native sockets, durable private files and runner restarts;
they do not redeem a live benefit or prove deployment/account binding.

The provider consume request accepts a credit/key pair but no account ID. Account
binding is checked against the preceding native account/usage read; it is not an
atomic provider-side account predicate. Preserve the single existing session and
avoid changing accounts during an admitted invocation. This implementation does
not claim stronger provider guarantees than its idempotent endpoint provides.

## Historical one-off compatibility

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

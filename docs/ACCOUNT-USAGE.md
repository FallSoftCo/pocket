# Account usage in NextComp

The usage control shows weekly included allowance and its reset deadline. When that allowance is exhausted and a finite credit balance is reported, its primary number shows compact remaining credits. Open Settings/usage for the exact balance, continuation status, last observation time and a labeled comparison period.

The authenticated `GET /api/status` response and existing `rateLimits` WebSocket event include `usage.weekly`, `usage.credits` and `usage.continuation`. No additional model turns are used: the existing deduplicated account quota read runs at most once per minute while clients are connected. Credit freshness is tracked separately so a partial quota event cannot make an old credit balance look current.

Credit fields are allowlisted: decimal `balance`, nullable provider booleans `hasCredits`/`unlimited`, millisecond `updatedAt`, `stale`, `comparisonState`, and optional `observation {fromAt,toAt,balanceDecrease}`. Account IDs, authentication, authorization records and private ledger keys are never sent to clients.

Continuation distinguishes available included allowance, exhausted included allowance with existing-credit continuation available, credits present but continuation unverified, a provider spending/account block and unavailable evidence. Available continuation requires matching owner authorization and provider eligibility/control evidence. It is not a claim that an individual request has been billed to credits, and it never changes account settings or spending controls.

Private SQLite observations are bound to an HMAC of the signed-in account identity. Decimal differences are exact. Account switches, missing balances, unlimited access and balance increases cannot be aggregated into a spending total across that boundary. An increase starts a new comparison; it is not assumed to be a top-up. A comparison is an observed balance decrease over the displayed interval, across the account. It is neither lifetime spending nor per-session attribution. Purchases, adjustments and activity between observations can affect balances; there is no inferred currency conversion.

Official references: [Codex pricing and credits](https://learn.chatgpt.com/docs/pricing), [Codex app-server account endpoints](https://learn.chatgpt.com/docs/app-server).

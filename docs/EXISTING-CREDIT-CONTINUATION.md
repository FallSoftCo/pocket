# Existing account credits and continuation

Codex signed in with ChatGPT consumes included personal-plan usage first, then
eligible existing account credits. This is separate from API-key billing and
from automatic reload (a credit purchase). NextComp does not purchase credits,
consume rate-limit-reset rewards, enable reload, or change models for recovery.

A private `data/existing-credit-continuation.json` authorizes using existing
credits for one verified signed-in account:

```json
{"enabled":true,"accountId":"verified-account-id"}
```

Provision this only after explicit user authorization and a successful
`account/read` / `account/rateLimits/read` audit. Never commit this file.
Account changes clear the cached binding. Recovery requires fresh rate-limit
health, a personal plan, a positive backend-reported credit balance and an
explicitly clear spend-control state before continuing past included limits.
Depleted/unknown credits, individual spending limits and workspace caps remain
stops. Native task pauses and token/usage budgets also remain independent stops.

Definitive personal quota failures now enter the existing durable, deduplicated
recovery scheduler. It resumes preserved context with sparse bounded backoff;
it never repeats the original request. Unknown delivery still requires
reconciliation, and user cancellation wins. Exhausted quota without eligibility
is rechecked sparsely rather than unconditionally sleeping until weekly reset.

Qualification: exhausted-allowance eligibility, wrong-account binding, depleted
and unknown balances, stale/disconnected health, spending limits, privacy,
service-restart recovery and no duplicate dispatch are tested. No live allowance
was intentionally exhausted to test billing. Account UI may require a separate
browser login; CLI account evidence must not be presented as UI verification.

Official references: [personal-plan credit behavior](https://help.openai.com/en/articles/12642688-using-credits-for-flexible-usage-in-chatgpt-personal-plans)
and [Codex pricing](https://learn.chatgpt.com/docs/pricing).

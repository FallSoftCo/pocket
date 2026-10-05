# Select Codex or Losangelex

NextComp can connect to two execution backends in each paired environment. Choose **Codex** or **Losangelex** at the top of the Android interface. Switching changes the available interaction modes; it does not migrate or combine conversations.

| Backend | Available experience |
| --- | --- |
| Codex | Existing stock Codex sessions, persistent coordinator, native voice, steer/queue, model settings and supported questions/approvals. |
| Losangelex | Shared team room, task selection and creation, addressed room messages, direct conversations, replies, scoped Stop/Pause/Resume and team attention. Hollywood supplies communication and task routing behind this backend. |

The first Losangelex integration uses typed conversation controls. Its voice capability is reported as unavailable; the Codex voice controller never silently handles a team request. Existing stock sessions remain with their original runtime and histories. Losangelex agents use independently owned task contexts and the tools configured in that runtime. Selecting the backend does not import workstation plugins or turn existing stock sessions into team members.

## Connect the owner operated service

First provision and verify the Losangelex Hollywood v2 team service on the intended host. The original Hollywood v1 CLI message transport alone is insufficient. NextComp observes the existing team service and sends scoped commands; it does not start, replace or restart its Codex runtime.

Create `losangelex.json` in the private NextComp data directory (`POCKET_DATA`, otherwise `data/`):

```json
{
  "url": "http://127.0.0.1:18766",
  "tokenFile": "/absolute/private/hollywood-room/client-token"
}
```

Use the service origin without a path. Non-loopback HTTP is rejected; remote services require HTTPS. The token file must be an absolute owner-controlled path. Keep the registration and token out of source control. Device/model requests cannot select an arbitrary endpoint. Authorization to the team service stays on the NextComp host, and redirects are rejected.

Activate the registration with the normal NextComp backend rollout after its bridge operations and voice turns are quiescent. Do not restart the team service or stock Codex to enable it. Missing or malformed registration leaves Codex usable and shows Losangelex connection guidance. A valid but temporarily unavailable service keeps the selected backend and reports its connection error.

This remains one owner and trusted paired devices. Losangelex currently executes with full access and approval policy `never`; the interface labels that contract. Codex's review preference does not change team execution permissions.

## Direct work and review results

Choose the team and task, then a recipient. An unaddressed room message goes to the team's coordinator. Addressing a teammate in **Room** keeps the contribution public. **Direct** requires an explicit recipient and reads only that recipient's direct history. Reply binds the original message ID and author; clearing it restores the separate root draft.

**New task** creates fresh team task contexts. Assign work conversationally to the coordinator or selected agent; Hollywood's own tools perform delegation and peer messaging. **Agent** chooses an existing task participant for direct Stop/Pause/Resume controls. Stop requests interruption and pauses subsequent automatic work according to the team service's contract. A confirmed control receipt is distinct from a completed assignment.

Drafts and pending submissions are scoped by pairing, backend registration, team, task, visibility, recipient and reply. Saved submissions retain the command ID and exact payload after uncertainty. Retry the saved command rather than creating another copy. Acknowledgment means accepted, not completed. Switching profiles or backends never changes an in-flight request's credentials or target.

The room refreshes while visible without creating model turns. Earlier history remains available, and loaded older messages survive refresh. Agent contributions use the existing Markdown/image renderer. Needs you opens a paginated team inbox and checks the authoritative request state before answering. Approval acceptance is available only with supported concrete approval data. An uncertain approval is not automatically replayed.

## Background attention

The NextComp host periodically reads the team's durable pending-attention API. It creates a deduplicated NextComp notification and source mapping in the same database transaction, then uses the existing push outbox. This polling submits no runtime work. A failed or incomplete source scan cannot resolve existing attention.

Push previews say only that Losangelex needs attention. Task content is retrieved through the authenticated connection when opened. Notification IDs map to the originating backend registration, team, task and agent; the selected conversation cannot retarget them. Opening a stock notification selects Codex. Opening a team notification selects Losangelex and its exact request. Backend-token/origin changes invalidate old notification targets instead of redirecting them.

Answers use originating team routes and stable command IDs. Native Skip dismisses a team question; it cannot skip or approve an open approval. Resolved attention is reconciled back to NextComp. Existing Losangelex Android registrations remain independent: avoid registering the same phone in both apps when duplicate alerts would be undesirable.

## Implementation and evidence

- `server/losangelex.mjs` registers a fixed owner endpoint, advertises capabilities and exposes a narrow authenticated adapter. Account resets, APK/device management and arbitrary RPC forwarding are excluded.
- `server/losangelex-attention.mjs` maps source attention to the existing NextComp outbox and preserves notification origins.
- `BackendApp.kt`, `LosangelexClient.kt`, `LosangelexScreen.kt` and `TeamActions.kt` implement backend selection, captured credentials, scoped drafts, team history and controls.
- `tests/losangelex.test.mjs` checks authorization, registration isolation, exact-command retry, redirect rejection, attention deduplication and incomplete-scan recovery.
- `tests/fixtures/losangelex-ui.mjs` supplies a synthetic team service for owned emulator qualification. It never calls Codex. Use a disposable NextComp data directory and a separately owned emulator; do not point fixture controls at a live service.
- [Real runtime qualification](qualification/losangelex-live-backend.json) records a disposable Maya/Theo exchange through the actual adapter: separate roots, required peer reply with tool authority, retry identity, direct human attention, generic notification mapping and answer reconciliation.
- [Android qualification](qualification/losangelex-android-backend.json) records native synthetic interactions. Its fixture checks do not establish physical-phone push delivery or general agent performance.

Existing stock-session adoption, team voice, per-task team permission configuration and actuator fencing are separate capabilities. The external-thread association API is metadata-only; this release never acquires a stock session's writer or copies its rollout. Shared computer-use ownership remains at the host broker/actuator, independently of team admission limits.

# Architecture decisions

Pocket lets Codex notify its own user about its own work. Notifications lead into a two-way conversation with the original task. It is not an arbitrary-recipient messaging service.

- **Stock runtime:** an MCP notification tool and a local bridge connect to Codex's existing experimental shared app-server. No fork or competing runtime. Compatibility is explicitly versioned.
- **Same-task replies:** active turns use `turn/steer`, idle tasks use `turn/start`. Exact request IDs route native questions and supported approvals. Codex permissions remain authoritative.
- **Single owner:** pairing identifies trusted devices for one workstation account. The model never chooses recipients. This alpha intentionally has no multi-tenant hosting design.
- **Self-hosted application, Firebase transport:** the owner runs the backend and owns the Firebase project. FCM carries bounded previews; transcripts and attachments remain on the workstation. No FallSoft relay or app-store release is needed. Earlier foreground-service transport was replaced with FCM.
- **Durable outcomes:** replies, push delivery, and phone-created sessions have persistent state and stable IDs. Queued, accepted, failed, and unknown have different meanings. Ambiguous actions are not blindly repeated.
- **Chronology:** snapshots and streaming updates merge by stable turn/item IDs. Tool activity expands in place; history pages backward; manual scrolling pauses following. Raw reasoning is excluded.
- **Attention:** actionable events can remind with bounded approximate WorkManager timers, dismissal, and snooze. Ordinary completion/update alerts do not repeat. Generic speech is synthesized offline in advance, never from task text.
- **Distribution:** MIT source, sideloadable signed Android alpha, and owner-operated infrastructure. Release signing keys remain private and stable across updates. Public release artifacts never include runtime state or local review captures.

The app-server protocol is version-sensitive. See [official protocol documentation](https://developers.openai.com/codex/app-server/) and the installed CLI's generated schema when adapting it. The implemented target and actual test evidence are in VERIFICATION.md.

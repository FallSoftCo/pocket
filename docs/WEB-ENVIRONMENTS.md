# Unified browser environment catalog

The browser catalog combines the primary workstation with explicitly provisioned remote environments. Each remote session has an opaque environment-qualified ID. The gateway resolves that ID to its original native ID and the original browser's paired credential on that host. Titles never select a destination.

Backend catalogs and message pages remain authoritative. The gateway does not run agents, resume sessions, merge histories across hosts, or replay task delivery. A source outage retains its last known cards with an unavailable state. Reconnection uses source-bound event streams and periodic read-only reconciliation; one source's status cannot overwrite another's connection state.

Replies, steer/queue, controls, approvals, native recording receipts/cancellation, and API attachment links retain their source. Pending send receipt checks use the saved destination, including after a page reload or navigation. Original local IDs and drafts remain unchanged. Remote IDs are qualified before entering the shared session store; identical native IDs on two hosts stay distinct.

## Credentials

WEB_ENVIRONMENTS_FILE names an owner-private version1 configuration with profiles keyed by the authenticated primary browser device ID. Each profile contains a stable environment id, display name, private HTTPS base, paired remote deviceId and paired token. It may specify an owner-controlled provisionCommand argv. Credentials never enter browser JSON or static assets. Owner credentials are rejected as remote browser grants. Primary authorization is checked before every remote operation, and remote rejection does not sign out the primary browser.

An optional provision command receives only the validated primary device ID. It may issue an independent browser pairing through an existing host-local owner path, retaining immutable intent and pairing receipts. It must not reuse phone tokens, copy device databases, transport administrator secrets, silently regrant revoked pairings, or create model work. The installed host-local issuer is private operator configuration, with its exact source and receipts retained privately. Concurrent preparation is bounded; unavailable provisioning backs off.

The current delivery connects the existing workstation and Mac browser paths, using existing cookies and private origins. It also retains the older compatibility origin and its migration path. New-task creation retains its primary-workstation default; this change does not claim a new environment chooser or complete browser voice parity.

## Empty and temporary entries

Missing titles do not prove an empty task. Contextual display labels use existing previews, agent names or workspace names without renaming native sessions. A session is folded into Not started only when a complete native observation explicitly has no earlier page, no message rows, no pending request or delivery and no active state. Temporary work is folded only after a positive ephemeral declaration; child agents remain reachable under their parent.

Classification is bounded to four outstanding observations, two concurrent reads and a350ms catalog wait. It never waits for a whole history or resumes a runtime. Unknown legacy metadata stays visible with a contextual label. Explicit boolean resets and current activity prevent an old empty/unavailable flag from hiding work that has resumed.

## Qualification

Actual HTTP authorities test identical native IDs, source-specific replies and questions, credential revocation, binary recording upload, opaque JSON attachment delivery and source isolation. A rendered Chromium journey uses synthetic content for open/reply, a lost reply response followed by reload without resubmission, original local drafts, live remote events, source-bound linked images and the empty/temporary filters. Real read-only gateway qualification found and opened the user's Mac session without posting to it. Native Mac-window interaction and physical audio are separate evidence, not claimed by these browser fixtures.

An optional owner-configured `WEB_CATALOG_STORE` provides read-only compatibility for native metadata reporting `thread not loaded`. It reads only the exact existing canonical discovery declaration of temporary work, never message history, and does not resume a thread or write a parallel catalog. Unknown or missing declarations stay unknown.

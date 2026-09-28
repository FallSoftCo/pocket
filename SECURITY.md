# Security

## Supported release and reporting

Only the latest public alpha receives fixes. Pocket is experimental and has not had an independent security audit.

Report vulnerabilities privately using [GitHub private vulnerability reporting](https://github.com/fallsoftco/pocket/security/advisories/new). Do not put credentials or exploitable details in public issues. Include the Pocket/Codex versions and a minimal reproduction using synthetic data.

## Trust boundary

A paired phone is a trusted controller for the owner's Codex account. It can see the paired host's listed sessions, submit work in accessible project directories, reply, stop tasks, and answer supported requests. There is no per-project isolation or multi-tenant authorization layer. Codex remains responsible for its configured sandbox and approval policies; Pocket does not make an unrestricted Codex configuration safe.

Run the backend as the same OS user as Codex, bound to loopback behind authenticated private-network access and HTTPS (such as Tailscale Serve). Do not expose the raw Codex socket. Public internet hosting, anonymous users, and shared team backends are outside this alpha's deployment scope.

## Controls

- Random one-use pairing codes expire after 15 minutes; pairing attempts are rate limited.
- Device bearer tokens are random, stored hashed on the server, and required for HTTP and WebSocket access. Owner-only endpoints create codes, list/revoke devices, and invoke the local notification tool.
- Revocation removes push registration and closes active sockets. It does not undo already submitted Codex work or erase delivered phone content.
- Native approval answers use exact pending IDs and allowed decisions. Resolved/restarted requests expire. No automatic approvals.
- HTTPS is required by the Android client, cleartext is disabled, and app backups are disabled.
- Phone-local mode permits cleartext only for the fixed `127.0.0.1:18880` loopback endpoint. Its automation service binds only to `127.0.0.1:18881`, requires a random per-install secret, and refuses calls unless both Android Accessibility and Pocket's independent control switch are enabled.
- Phone screen snapshots omit password text and phone text entry refuses password fields. Read tools are locally approved after the owner enables both controls; phone actions retain Codex approval handling. Accessibility cannot bypass Android secure surfaces, biometric prompts, or app sandboxing.
- The exported audio provider serves only allowlisted generic sound clips. Attachments require authentication; only the owner/MCP path can copy explicitly supplied local files.
- Requests and attachments are size bounded. Durable reply/session creation records prevent ordinary duplicate submissions; uncertain RPC outcomes are surfaced for review.

Keep service-account keys, `data/`, signing keys, owner tokens, and phone-local automation secrets private. The MCP tools are local privileged interfaces: the model must only attach files or take consequential phone actions explicitly requested by the owner. On Android, `danger-full-access` is required because the desktop workspace sandbox is unavailable under PRoot; Termux's Android permissions become the filesystem boundary. Do not treat Pocket as a sandbox against a compromised local process, compromised phone, malicious screen content, or malicious trusted Codex workload.

## Release review

The initial release review covers authentication, code replay, owner/device separation, revocation, WebSocket access, native request routing, credential packaging, and signing. Automated fixtures test these paths; physical testing covers a narrower set described in [verification](docs/VERIFICATION.md). Passing tests and dependency scans are not proof that the application is free of vulnerabilities.

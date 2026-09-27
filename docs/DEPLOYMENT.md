# Deployment and troubleshooting

## Configuration

Environment variables are read directly; `.env` files are not automatically loaded.

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | `18880` | Loopback backend port |
| `POCKET_DATA` | checkout `data/` | Absolute private state directory; use consistently in server, tools, and scripts |
| `CODEX_HOME` | `~/.codex` | Codex home for socket discovery |
| `CODEX_SOCKET` | `$CODEX_HOME/app-server-control/app-server-control.sock` | Existing shared app-server Unix socket |
| `POCKET_PUBLIC_URL` | required for pairing command | Phone-reachable HTTPS origin |
| `POCKET_URL` | `http://127.0.0.1:18880` | MCP/owner-command backend URL; owner scripts require loopback |
| `GOOGLE_APPLICATION_CREDENTIALS` | `data/firebase-admin.json` | Private Firebase sending credential override |

Use an absolute `POCKET_DATA`, especially for systemd/MCP. If no local sending credential exists, Firebase Admin can use Google application default credentials. Client configuration must still exist in the data directory.

## Linux background service

Copy `deploy/codex-pocket.service.example` to `~/.config/systemd/user/codex-pocket.service`. Replace every `REPLACE_...` placeholder with an absolute path. Use the Node binary returned by `command -v node`; systemd does not load your interactive shell or nvm profile. Ensure Node is 22.13 or newer. Protect any custom environment file with mode 600.

```bash
systemctl --user daemon-reload
systemctl --user enable --now codex-pocket.service
systemctl --user status codex-pocket.service
```

If you already ran `npm start`, stop that foreground instance before starting the service. Check `journalctl --user -u codex-pocket -n 50` for startup failures. Enabling user lingering is an optional OS-admin choice if this must run after logout; Pocket still needs the shared Codex runtime available.

Register a custom-data MCP connection with explicit environment options:

```bash
codex mcp add pocket --env POCKET_DATA=/absolute/private/pocket-data --env POCKET_URL=http://127.0.0.1:18880 -- node /absolute/path/pocket/server/mcp.mjs
```

## Diagnosis

Before cloud setup, run `npm run doctor -- --preflight`. It checks Node, Codex presence and socket/protocol access without requiring Firebase or the Pocket backend. After starting Pocket, run `npm run doctor` with the same environment as the backend; it also checks Firebase configuration, backend health and the backend's live Codex connection. Neither mode submits a task or prints tokens.

- **Missing socket:** start your authenticated Codex CLI normally. Confirm the tested version and shared-runtime support. A different/newer installation may not expose this experimental transport. Do not create a separate app-server and expect it to control an existing live CLI task.
- **Cannot reach workstation:** both devices need their private network connected, HTTPS reachable, and the backend running. Check Tailscale Serve's printed URL and port.
- **Push registration retries:** verify Google Play services, project/API configuration, Firebase client settings, and sending credentials. Open Pocket again after force-stop. A test notification accepted by FCM is not proof the phone displayed it.
- **Reply queued:** the durable request is waiting for Codex. Keep the workstation online. Unknown outcomes require checking the conversation before resubmitting.
- **Wrong signing certificate on upgrade:** follow RELEASING.md. Do not uninstall until you understand which local drafts/settings will be lost.
- **Lost phone:** list and revoke it with `scripts/devices.mjs`. Server revocation is required even if a disconnected phone clears its local pairing.

For source updates, stop Pocket (not Codex), back up the private data directory, install dependencies with `npm ci`, test, and restart Pocket. Completion/failure alerts for followed tasks are reconciled on reconnect and deduplicated by turn ID. On the first upgrade, existing completed history becomes the baseline; it is not replayed as new notifications. Restarting expires native approval requests owned by that bridge connection; handle unresolved ones in the terminal. Current schema creation is additive; future releases must document migration requirements.

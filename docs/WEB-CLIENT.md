# NextComp on the web

The browser client reuses the running NextComp backend and stock Codex environment. It is designed for keyboard/mouse use on a Pixelbook Go, with a single-column touch layout and an installable web app manifest. The coordinator and direct session input remain separate. Opening a delegated agent exposes its actual parent and permits input only when the runtime explicitly advertises direct-input support. A fork is not treated as an agent parent.

## Private deployment

Run the gateway as the same workstation user as NextComp. It binds only to loopback and proxies ordinary paired-device requests to the existing backend. It never loads the backend's owner token. Point a **tailnet-only** Tailscale Serve HTTPS listener at it; never enable Funnel or expose either listener publicly.

```sh
WEB_ORIGIN=https://YOUR-PRIVATE-HOST:8448 \
POCKET_URL=http://127.0.0.1:18880 \
WEB_PORT=18882 node server/web-client.mjs
```

Set `WEB_DATA` to an absolute private directory to override `~/.local/share/nextcomp-web`. Its mode600 `cookie-key` preserves browser pairing across gateway restarts. Back it up privately; never include it in source or an artifact. Start with [the service example](../deploy/nextcomp-web.service.example). This separate gateway requires no Android upgrade, backend data migration or backend restart.

Pair using the ordinary one-use code from `scripts/pair-device.mjs` with the existing backend deployment environment. The browser gets a separate device identity; it does not reuse the phone's bearer. Device bearer material is kept in a signed Secure HttpOnly SameSite=Strict cookie. Pairing responses remove the bearer from JSON. Writes and WebSocket upgrades require the exact configured origin. The gateway's key authenticates its cookie; backend device revocation remains authoritative. Unpair clears the cookie and this browser's saved drafts/recording; workstation device revocation is required for a lost device.

## Interaction

- Coordinator text uses the existing device-scoped voice-controller conversation; direct text uses the session outbox. Enter sends/steers, Shift+Enter inserts a line, and the delivery selector queues later input. Ctrl/Cmd+K focuses work search.
- Drafts are isolated by paired device and conversation. Durable request IDs are saved before sending. On reconnect the browser first checks that exact ID, and repeats an absent HTTP submission with the same ID. Backend unknown-delivery states require explicit review/retry and duplicate-risk confirmation. Task creation follows the existing durable creation outbox. This is not an exactly-once guarantee.
- Conversation rows merge by stable item IDs/revisions. Older fetched pages stay available through live refreshes and switching; paging can trigger near the top. Manual scrolling pauses following; Latest returns to current work. Important filters the same timeline. Intermediate answers retain contextual reply and explicit native speech actions.
- Work seats remain stable during metadata/preview refreshes. Refresh or returning to the touch work list can reconcile order. Work time uses normalized `recencyAt`, `activityAt`, `createdAt`; metadata `updatedAt` never makes work recent. The deployed session catalog is authoritative: the gateway passes through its work recency, parent/input capability, live status and pagination without rereading or overwriting runtime metadata. Set `WEB_LEGACY_METADATA=1` only when explicitly bridging an older backend; leave it unset with the canonical session catalog. Unsupported child history uses the existing `ThreadHistory` reader without `thread/resume` or writer ownership. Child guidance requires explicit capability, and parent navigation never moves/replays its draft.
- Supported native questions and command/file approvals use the exact pending request ID and offered decisions. Expired requests fail without being converted into a new request. Attention opens its original work; scheduled reports have a separate paginated Work updates view, explicit read actions and per-browser scheduling preferences in Settings. Unsupported request kinds remain terminal-only.
- Session controls include follow/unfollow, rename, interruption, queue resume, archive/restore and offered next-turn model/effort/Plan settings. Execution environments and permissions are still governed by the backend and stock Codex.

## Voice and browser limits

The voice transport adapts the existing Android `native-voice.html`: WebAudio creates a silent sender track; manually captured browser audio is buffered, then played into the native WebRTC connection after the user selects Send recording. The native backend transcribes and submits the resulting coordinator/direct turn. No account or audio API key reaches the page. Audio capture requires a supported secure browser and explicit microphone permission. Saved recordings remain privately in IndexedDB across reloads, scoped to their original conversation. Ambiguous audio delivery is held for review instead of automatically replayed. Explicit Speak starts native account playback; Stop speech closes it. Browser autoplay, account/provider availability, delayed transcription and speech quality remain qualification limits.

The manifest and icons support browser installation where available. The service worker installs without caching authenticated responses, transcripts, recordings or drafts in CacheStorage. Offline browsing/execution is not supplied. Draft editing remains local while disconnected, and delivery status distinguishes accepted requests from completed work.

Browser notifications are opt-in, generic previews while the WebSocket is connected. Background tabs may be suspended; closing the browser ends delivery. No web push, guaranteed background microphone/audio or closed-app alert delivery is claimed. Keep the Android notification return path for these cases. See [MDN installability](https://developer.mozilla.org/en-US/docs/Web/Progressive_web_apps/Guides/Making_PWAs_installable) and [background operation](https://developer.mozilla.org/en-US/docs/Web/Progressive_web_apps/Guides/Offline_and_background_operation).

## Validation

`node --test tests/web-client.test.mjs` tests pairing, cookie integrity, owner-route exclusion, origin checks, device-authenticated WebSockets, delegated capability gating and stable work recency. `scripts/web-browser-check.mjs desktop|touch` exercises the actual gateway and rendered client against synthetic backend data; use a task-owned browser under the configured computer-use broker, preserving existing tabs and input. Set `PLAYWRIGHT_MODULE` to an installed Playwright module. Synthetic data checks do not establish actual provider speech or physical Pixelbook behavior. The versioned qualification record distinguishes these from private live deployment checks.

Current qualification: [web-client-2026-10-07](qualification/web-client-2026-10-07.json) records327 passing backend/gateway tests, synthetic virtual-DOM checks and actual private HTTPS task creation/continuation. The deployment-configured browser resource is quarantined by an earlier unrelated uncertain action, so rendered-browser, physical Pixelbook and browser-provider voice results are not claimed. The prepared desktop/touch checks can run when a qualified resource is available.

Integration review adds backend authentication before runtime metadata admission and preserves partial-inventory cache state. Seven gateway tests cover these cases; the full backend/gateway suite contains329 tests. Rendered-browser and physical Pixelbook qualification remains outstanding.

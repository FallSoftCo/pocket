# Shared computer-use ownership

NextComp delegates task execution to the Codex app-server. It does not directly
drive Playwright, the desktop, or computer-use tools. Locking an entire Codex turn
would block independent coding and would miss workers launched elsewhere.

Install the shared host broker separately and configure `COMPUTER_USE_CLIENT_PATH`
to its `client.mjs` module. The app does not assume a private checkout path.
Configure `COMPUTER_USE_BROKER_URL` (default `http://127.0.0.1:9340`) and
`COMPUTER_USE_BROKER_TOKEN` on the host. Authenticated `GET /api/computer-use`
returns availability and safe aggregate counts, never lease tokens or owner data.
Missing credentials or an unavailable broker prohibit computer control under the
cooperative policy; unrelated thread execution continues. Worker
shells source the deployment-provided private `$HOME/.config/computer-use-broker/worker.env` without
printing it. The application server does not automatically load worker credentials.

For persistent deployment, the service owner can add a private unit drop-in with
`EnvironmentFile=%h/.config/computer-use-broker/worker.env` to the appropriate
user service at its normal maintenance restart. System services must use the
owner-configured absolute path instead of `%h`. Configure the worker/Codex service
as well as the NextComp service if they run separately: NextComp credentials allow
its status request, while the worker needs credentials to acquire ownership.
Keep that file out of source control and never include its contents in logs.
This change does not install a drop-in, modify live environment, or restart a service.

New NextComp task and voice threads receive additive acquisition instructions.
Existing task resumes preserve their instructions and active sessions. Voice
resume retains its existing voice policy. NextComp does not acquire turn-wide
locks or interrupt sessions when the broker is unavailable.

Workers import `ComputerUseClient` from `COMPUTER_USE_CLIENT_PATH`, identify the app
and thread/task as `owner`, and use `withLease` plus `lease.withAction` around
bounded observation/input transactions. Background DOM actions can run on
distinct owned pages. Foreground actions must also acquire the desktop resource;
profile/account changes acquire the browser profile resource. Use only configured
canonical names from `COMPUTER_USE_DESKTOP_RESOURCE` and
`COMPUTER_USE_BROWSER_PROFILE_RESOURCE`, configured by the host deployment. Append `:page:<target>` to that
profile resource for owned background DOM control. Never guess aliases; missing
resource configuration prohibits the corresponding control action. Acquire the full
resource set atomically, observe again after queueing, preserve focus/input state,
confirm the native worker stopped and safely clean up your own input/focus before
returning from `withAction`, and release before reasoning or waiting on external
work. Continuous lease tenure is capped at 30 seconds; renewals cannot extend
that cap, and active actions drain safely. An exception quarantines uncertain
actions rather than replaying them.

This app integration is cooperative. Existing Codex tools and direct desktop
commands are not fenced by this HTTP app. Enforced arbitration must run at their
actuator/bridge boundary using the same shared contract. The shared broker tests
cover actual concurrent leases and recovery; NextComp tests cover safe status,
new-thread propagation, authentication and continued independent task behavior.

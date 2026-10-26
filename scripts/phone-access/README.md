# Phone SSH recovery

Installed on the existing F-Droid Termux/Boot/API setup on 2026-10-26. These are
exact small runtime scripts, not an APK or a second model supervisor.

`ensure.py` is installed as `~/.local/bin/phone-access-ensure`. The helper is **manual-only**.

**Do not schedule it periodically.** The initial job8022 was cancelled after
actual live use showed visible Termux task/session notifications on every
callback despite no repair being needed. Phone uptime was14days; SSH, backend
and Codex PIDs stayed stable. No recurring replacement was installed.

Do not cancel unrelated jobs.
The helper is single-flight, refuses a second living SSH daemon/listener, respects
service down state and its own disabled marker, and limits repair attempts to
one per five minutes. It never starts Codex or the local application backend.
A surviving orphan runit controller is left alone rather than restarting its
healthy service tree. Whole-app death still depends on Android allowing the job.

`sshd-run` and `sshd-finish` install into `$PREFIX/var/service/sshd/`. The finish
script records exit information and backs off from 2 to 300 seconds; 120 seconds
of healthy runtime resets the backoff. Preserve existing SSH config and keys.
`10-phone-access` replaces the boot entry; the redundant existing boot20 script
was backed up outside the boot directory. Normal runit enabled/disabled state
continues to own other services. Do not enable additional services.

Actual phone verification: one controlled SSH daemon termination, subsequent
fresh authenticated SSH connection, original local backend and Codex processes
unchanged, repeated helper calls performed no repair, job8022 registered/pending
and an initial automatic callback observed. No device reboot or force-stop was
tested. The installed Termux API has a null NetworkRequest formatting bug with
`--network none`; using `any` produced successful registration/readback.

The historical OS kill reason was not available to ordinary Termux. Current
SSH uptime was about three days; the parent directory supervisor was absent,
while individual controllers survived. Do not report confirmed OOM, phantom
process killing or VPN failure from those observations alone. No global Android
process safeguard, account, model, pairing or permission was changed.

First unlock after reboot may be needed. Force-stop can require reopening the
apps. Android can defer jobs, including under low battery/network constraints;
a persisted flag is not proof of reboot persistence. Keep the established
battery exemptions and Tailscale always-on setting; this change does not bypass
an explicit OS stop or bring a disconnected VPN online.

Disable this job with `termux-job-scheduler --cancel --job-id 8022`.
Set `~/.local/state/phone-access/disabled` to suppress helper repairs. Protected
before-files on the phone are in `~/.local/state/phone-access-recovery-20261026/`;
restore only owned files and retain the current service/control state.

Correction: earlier registration/pending/callback evidence below is historical,
not current activation. Current pending-job readback says No jobs found. Boot
startup and runit SSH crash backoff remain active; no service restarted during
this correction. Whole-app death recovery remains OS-dependent/manual.

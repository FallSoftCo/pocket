# Phone SSH lifecycle

The live configuration uses the supported Termux foreground service and wake
lock, one Termux:Boot script, and termux-services/runit supervising sshd.
There is no periodic checker, custom repair supervisor or model activation.

- Install `10-phone-access` in `~/.termux/boot/`, preserving backups and service
  enabled/disabled state. It acquires the existing foreground service wake lock
  and invokes the official termux-services startup hook. Do not add a second
  direct sshd launch or force-enable deliberately disabled services.
- `sshd-run` and `sshd-finish` belong in `$PREFIX/var/service/sshd/`. The finish
  script records exit information and backs off2..300seconds after short
  failures;120seconds healthy runtime resets it. Existing SSH keys/config remain.
- The original duplicate boot20 entry was backed up outside the boot directory.
  Other enabled services remain owned by their existing runit controllers.
- Retired: custom ensure.py helper and Android job8022. Every periodic callback
  created visible Termux task activity despite no repair being needed. Current
  actual pending-job readback says No jobs found. Do not recreate that job or a
  replacement shell watcher. The historical code remains in Git only.

Actual evidence: one controlled SSH daemon termination recovered and a fresh
independent authenticated SSH connection passed. Subsequent observation showed
same SSH/backend/Codex processes, phone uptime14days, no recurring SSH restart.
The final cleanup did not restart a service or local worker. No reboot or
force-stop was tested. The absent parent directory supervisor is not reason to
restart healthy surviving controllers or their productive children.

Termux already uses an Android foreground service, so one ongoing notification
is expected. Upstream TermuxService returns START_NOT_STICKY; stock whole-app
resurrection is not guaranteed. Reboot may need first unlock; explicit force-stop
may require reopening Termux. Preserve Android global process safeguards and
existing F-Droid app identities/data. A differently signed fork cannot be treated
as a safe in-place update. Existing battery exemptions/always-on VPN were
previously configured, not independently re-read during this change.

Official source:
https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/app/TermuxService.java
https://github.com/termux/termux-boot/blob/master/README.md

Before-files and actual receipts are retained privately on the phone in
`~/.local/state/phone-access-recovery-20261026/`. No pairing/auth reset is needed.

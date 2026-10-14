# Gateway update delivery and continuity

Host: OZZZ-OS (`ozzz-aosp`). Repository: `/aosp/development/lax-upstream-20261013/nextcomp-source`. Source changes are confined to `server/app-updates.mjs` and `tests/app-updates.test.mjs`: permit serving the already authenticated, signer-selected, checksum-verified, path-confined APK beneath a private hidden directory. Eleven focused tests passed. This code hotfix is staged, not deployed.

The actual download failure was resolved without deploying code or restarting a service. The configured channel `/home/aosp/.local/share/nextcomp-backend/data/app-updates` now points to owner-private mode700 `/home/aosp/nextcomp-private-updates/20261014-channel115`. Existing `AppUpdates.artifact()` canonicalizes that root and the APK before confinement and hash checks; Express receives a nonhidden canonical path. The previous directory remains at `/home/aosp/.local/share/nextcomp-backend/data/app-updates-held-download404-20261014` with its manifest, APKs and publication journal intact.

Before the latest hold, authenticated live GET returned200 with the complete62194734-byte compatible115 APK, SHA256 `74709e19eadc0348e9b142c1714b3989fb59008dab4176b50ac4c1bf47170238`. Live unauthenticated access returned401; traversal signer400; unknown signer, other-file path and encoded traversal path404. Notification78 had already been offered once and accepted by FCM before Global2468 arrived. No further announcements are sent. Global2468 was enforced by holding the manifest as `manifest-115-held-global2468.json`; Global2583 subsequently released that hold within the original publication authority.115 is now active again. Both offered variants returned authenticated live200 with their full matching qualified SHA256 and byte counts; unauthenticated/traversal/other-file checks passed again. Offer reconciliation returned announced0 and reused notification78, whose single FCM receipt remains accepted. Physical phone banner/download/installation remain unobserved.

## Observed process boundaries

`nextcomp-ozzz.service` PID861885 uses `/usr/bin/node /home/aosp/.local/share/nextcomp-backend/releases/20261013-rooms-voice-r01/server/index.mjs`, with working directory `/aosp/development/nextcomp-control` and existing private `POCKET_DATA`. Its cgroup is `/user.slice/user-1000.slice/user@1000.service/app.slice/nextcomp-ozzz.service`; KillMode is control-group. Its observed children are861895,906999,1035587.

`lax-architecture.service` PID861703 is the Hollywood Python server (`hollywood_room.server`), in separate cgroup `/user.slice/user-1000.slice/user@1000.service/app.slice/lax-architecture.service`, with child861733. Both service PIDs remain unchanged and active. Process separation is observed; it does not prove that every Global tool dependency is independent of gateway availability. Exact Global-context mapping was requested from the Hollywood owner. A gateway restart would recycle its own stock Codex bridge and other cgroup children, so their active turns, native questions, voice and pending operations must be assessed before any restart. A notLoaded catalog entry is insufficient evidence of an idle runtime.

## No-restart release path, only after hold release

Keep the canonical channel pointer. Verify the held manifest still matches the qualified115r03 stage, all artifact hashes remain exact, the live service PIDs are unchanged and no active manifest or competing publication exists. Then restore this already published manifest; do not replay the publication pipeline against its existing publication journal:

```sh
set -eu
test ! -e /home/aosp/nextcomp-private-updates/20261014-channel115/manifest.json
mv /home/aosp/nextcomp-private-updates/20261014-channel115/manifest-115-held-global2468.json /home/aosp/nextcomp-private-updates/20261014-channel115/manifest.json
```

Authenticate a complete live APK download and compare both SHA256 and byte count before any additional offer. Notification78 already exists; do not send another announcement merely to prove delivery. Retain immutable original receipts. Holding again renames only the active manifest, preserving APKs and journals.

## Optional code rollout, not authorized or needed for this channel

The reviewed staged backend `/home/aosp/.local/share/nextcomp-backend/staging/20261014-update-download-r01` differs from the active release in `server/app-updates.mjs` only, module SHA256 `d039ed8e1f6c04d57c77c1a3aad67ea7532a665156865575cf5de62e05b57499`. Its node_modules link retains existing dependencies and its package lock is pinned in `continuity-plan.json`. Stop at any active or unresolved gateway-owned runtime dependency; do not infer permission from process separation. The following is a reviewable command proposal, not executed work:

```sh
set -eu
test ! -e /home/aosp/.local/share/nextcomp-backend/releases/20261014-update-download-r01
test ! -e /home/aosp/.config/systemd/user/nextcomp-ozzz.service.d/115-download.conf
cp -a /home/aosp/.local/share/nextcomp-backend/staging/20261014-update-download-r01 /home/aosp/.local/share/nextcomp-backend/releases/20261014-update-download-r01
mkdir -p /home/aosp/.config/systemd/user/nextcomp-ozzz.service.d
cat > /home/aosp/.config/systemd/user/nextcomp-ozzz.service.d/115-download.conf <<'EOF'
[Service]
ExecStart=
ExecStart=/usr/bin/node /home/aosp/.local/share/nextcomp-backend/releases/20261014-update-download-r01/server/index.mjs
EOF
systemctl --user daemon-reload
systemctl --user restart nextcomp-ozzz.service
```

After an explicitly scoped decision and idle/drain preflight, check gateway health, the authenticated full-body download, retained data/receipt state and unchanged LAX/Hollywood service identity. Never restart LAX/Hollywood to apply this patch. If the gateway-only rollout fails, verify the drop-in still contains exactly the above owned override, then roll back only that override:

```sh
rm /home/aosp/.config/systemd/user/nextcomp-ozzz.service.d/115-download.conf
systemctl --user daemon-reload
systemctl --user restart nextcomp-ozzz.service
```

The original unit then selects the prior release. Preserve the current database, pairing/tokens, drafts, accepted/queued/unknown journals and held update manifests throughout rollback. Do not restore an old database, replay uncertain commands, uninstall/re-pair the phone or replace Global history. No restart command in this document has been executed.

# Fleet server and local backend delivery

This is additive development infrastructure, not production/phone certification.
Fleet membership and service identity are operator-enrolled. No device database,
human/admin credential or node private key belongs in a release artifact.

## Server configuration

- `POCKET_FLEET_CONFIG`: absolute protected JSON matching FLEET-ACCESS.md's public
  members plus `version:1,fleetId,accountId,nodeId,privateKeyFile`. Private PEM is
  Ed25519, root/service-owned, no group/other permission. Membership reloads on
  authentication; removing a member/key denies its subsequent proofs.
- `POCKET_BACKEND_ARTIFACTS_DIR`: separate immutable blob directory.
- Optional `POCKET_BACKEND_ARTIFACTS_KEY_FILE`: dedicated 32-byte hex secret in a
  protected file outside the blob/data stores. Normal transfers use node JWTs,
  not per-release copied capabilities.
- Optional `POCKET_APP_UPDATES_DIR`: absolute installation-owned existing signed
  APK feed. Sharing this directory does not share pairing/device databases and
  does not announce or install an update.

`GET /api/backends` adds the agreed public fleet metadata. Only an authenticated
paired device or valid fleet-device token can use `POST /api/fleet/session`.
JWT lifetime is 900s, type `nextcomp-device+jwt`, audience
`nextcomp-fleet:<fleetId>`. The device identity and its original home node survive
refresh on another gateway. Original home pairing IDs/database state are kept.
Remote aliases are stable fleet/account/home/device identities, never token hashes.

In deployed d8c0e5b, remote authentication and queued remote-device work check original-home status
using read-only `/fleet/v1/devices/<fleet-device-id>`, with a separate node JWT
scope `device:status` bound to that exact GET path/device. Removing the original
paired device revokes access across members, including refresh and after restart.
An unavailable home denies access rather than silently accepting stale revocation
state. Offline-home failover is therefore NOT certified by this implementation.
The next server candidate replaces this availability limitation with stable local
opaque enrollment and durable eventual denial catch-up; see the exact client and
receipt contract in [FLEET-SERVER-ENROLLMENT.md](FLEET-SERVER-ENROLLMENT.md).
Device credentials cannot satisfy node routes; node proofs cannot enter human APIs.

## Immutable transfer and receipts

Artifact manifest has exactly:
`{schema:"nextcomp-backend-artifact/v1",sha256,size,version,sourceCommit,releaseDigest}`.
Maximum archive/unpacked payload size is 512MiB; SHA256 is lowercase hex. Local
publication installs `<sha256>.blob` and `<sha256>.json` with atomic no-overwrite
links after complete hash verification/fsync. Interrupted temporary files cannot
replace or poison a canonical content address.

`GET`/`HEAD /backend-artifacts/v1/blobs/<sha256>` accepts a short-lived signed
node proof bound to source/target nodes, method, path, hash and `artifact:read`.
The optional HMAC capability is manifest/audience-bound, <=900s, and uses
`Authorization: Artifact ...` plus `X-NextComp-Artifact-Audience`. Credentials
are never URL query strings or output. Unknown paths, query paths, symlinks,
corrupt files and write methods are refused.

Run helpers locally with the existing service identity where a protected key is
needed; the operator authorizes that identity without revealing/copying its key.
For initial GPU enrollment only, the operator installs the reviewed standalone
`scripts/backend-bootstrap-fetch.mjs` and `scripts/backend-release.py` once. The
bootstrap fetcher uses Node standard-library Ed25519 signing (no JWT parsing or
verification, no dependencies/build on GPU), fixed machine-only proof scope, and
the same exact manifest/hash/read-receipt rules. It takes `--fleet`, `--source-node`,
`--store`, `--manifest`, `--operation`; it cannot activate software. Subsequent
closed releases contain the normal helpers and pinned jose verifier.

```
storage-build --peak-bytes 268435456 -- python3 scripts/backend-release.py build --source CHECKOUT --output OUT
node scripts/backend-artifact.mjs publish --store BLOBS --artifact OUT/HASH.tar.gz --manifest OUT/HASH.json
node scripts/backend-artifact.mjs fetch --fleet LOCAL_FLEET_CONFIG --source-node CPU_UUID --store STAGING --manifest MANIFEST --operation UNIQUE_READ_ID
python3 scripts/backend-release.py stage --root DEVELOPMENT_ROOT --archive STAGING/HASH.tar.gz --manifest MANIFEST
python3 scripts/backend-release.py activate --config OPERATOR_CONFIG --operation UNIQUE_ACTIVATION_ID --release-digest DIGEST --expected-previous PREVIOUS_DIGEST
```

The downloader verifies actual streamed size/hash, never follows redirects and
has no activation action. Interrupted reads retain their immutable intent; a lost
read receipt can be reconciled against the exact closed blob without HTTP replay.
Staging validates all archive entries before extraction, refuses every link,
special entry, duplicate, traversal and file/directory collision, bounds gzip
expansion, and verifies the complete per-file release inventory. Build flattens
only in-source regular dependency files; no archive symlinks are installed.

Activation config is operator-owned non-group/world-writable JSON with
`{root:<registered development root>,unit:<one of the two additive development
units>,health:"http://127.0.0.1:19427/health"}`. Root has `releases/` and
`receipts/`; `current` is a managed atomic pointer. Initial cutover/env/unit setup
is operator-owned, not permission to alter legacy/production services.

Durable activation intent precedes pointer/restart effects. A repeated original
operation only reconciles matching pointer AND actual ready digest; it never
repeats a restart. Otherwise it remains held. Rollback is explicit `--rollback`
with a NEW operation ID and exact current/previous digests, not an automatic retry.
An orphan lock requires stopped-executor/effect review; never steal it on timeout.
Keep both releases and original receipts through qualification. Public `/health`
reports releaseDigest/sourceCommit when built metadata exists.

Focused journeys: `node --test tests/fleet-artifacts.test.mjs
tests/fleet-gateway.test.mjs tests/backend-release.test.mjs`. These exercise actual
owned gateway restart/session/revocation, loopback transfer, interrupted reads,
lost receipts, unsafe archives and explicit rollback; they are not phone proof.

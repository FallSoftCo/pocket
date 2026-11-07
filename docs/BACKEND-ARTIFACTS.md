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
# Host-local enrolled artifact reader

`scripts/backend-bootstrap-fetch.mjs` plus `artifact-local-files.mjs` are a
maintained Node-stdlib-only local reader. They use an already-enrolled Fleet
identity and existing verified HTTPS blob route, not a new server or human API.
No per-release bearer grant is needed. They never activate code or start work.

As the existing local Fleet service identity (or its explicitly authorized
operator), establish one private binding:

```
node backend-bootstrap-fetch.mjs --provision-reader --fleet /protected/fleet.json --node EXISTING_FLEET_NODE_UUID --reader /private/artifact-reader.json
node backend-bootstrap-fetch.mjs --check-reader --reader /private/artifact-reader.json
node backend-bootstrap-fetch.mjs --reader /private/artifact-reader.json --manifest /staging/artifact-manifest.json --source-node SERVING_FLEET_NODE_UUID --store /staging/downloads --operation ORIGINAL_OPERATION_ID
```

Provisioning checks the protected config, existing Ed25519 private/public-key
match and expected Fleet node. It writes a private policy referencing the
original config and pinning node/fleet/account/public-key identity; it never
creates, prints or copies a key. Fleet roster/origin changes remain governed by
the existing protected enrollment configuration. A changed identity requires a
new explicit reader binding; the old policy is not silently rewritten.

Keep the two code files together and root/owner-protected. Existing `--fleet`
reads remain compatible. Symlinked release CLI invocation runs the actual main;
module imports remain inert. Every fetch keeps its original content address and
operation receipt. A lost verified receipt is recovered from actual immutable
bytes without another HTTP request; no restart is implied.

POSIX protection checks owner/mode. Windows code uses actual owner/allow-ACE
inspection via the installed PowerShell, never POSIX mode bits or ACL repair;
that adapter requires local Windows qualification before a deployment claim.
The Linux test uses a real child CLI through a release symlink, verified HTTPS,
an enrolled synthetic node proof, a published immutable fixture and original
receipt recovery. It is not evidence of enrollment or activation on every host.

Missing local Fleet configuration/key authority is a provisioning prerequisite,
not permission to reuse Hollywood federation tokens, another host's identity or
expired grants. These are distinct credential/trust domains even when host UUIDs
coincide. Record the exact original owner boundary instead of reenrolling.

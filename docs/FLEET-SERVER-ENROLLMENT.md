# Local fleet enrollment contract (server candidate)

This contract is implemented in `server/fleet-enrollment.mjs`. It supersedes the
original-home-per-request availability limitation of the deployed d8c0e5b server.
It is a separate candidate until an operator deploys it; do not infer production,
GPU readiness, client release or phone installation from source/fixture proof.
Android/web and FLEET-ACCESS.md remain separately owned by the source coordinator.

## Agreed client exchange

Authenticated `/api/backends` fleet metadata retains `id`, `accountId`, `nodeId`,
`members`, `sessionPath` and adds:

```
enrollmentPath: "/api/fleet/enroll"
enrollmentReceiptPath: "/api/fleet/enrollments/{operationId}"
revocation: {consistency:"eventual",catchupIntervalMs:15000,unreachable:[nodeIds]}
```

1. An existing paired device calls `POST /api/fleet/session` on an available
   authenticated connection. A locally enrolled opaque credential can issue the
   same portable session, even while the original pairing gateway is stopped.
2. Send ONLY that portable `nextcomp-device+jwt` to a newly discovered member.
   Do not send another gateway's opaque token. All issuer, audience, EdDSA/type,
   expiry, fleet, owner-account and enrolled-member checks remain mandatory.
3. `POST /api/fleet/enroll` accepts exactly `{operationId}`. Generate one stable
   operationId per intended enrollment target, 8..100 ASCII letters/digits/`_-`
   (UUIDs are supported). Do not replace it after an unknown response.
4. `GET /api/fleet/enrollments/:operationId` reads the original receipt using a
   valid portable session or the same enrolled local credential. Missing original
   operation returns 404. A different device/generation cannot recover it.
   Refreshing the portable JWT at another enrolled node does not change receipt
   ownership. A repeated POST with the original ID has the same result/credential,
   not another device or authentication mutation.

Both POST enrollment and GET receipt return exactly this private response:

```
{version:1,fleetId,accountId,nodeId,
 deviceId:<stable LOCAL API device ID>,
 ownerDeviceId:<stable GLOBAL owner-device ID>,
 token:<LOCAL opaque bearer>}
```

Store this token only for this member. It is not a rotating JWT and does not
retarget profile IDs, drafts, outgoing operations, preferences or room ownership.
Existing home pairing IDs/token hashes/database rows are not replaced or copied.
Remote local aliases retain their preexisting stable IDs. Logical owner identity
includes fleet/account/ownerDeviceId, independent of node/transport/token refresh.
Credentials/receipt responses are private; never put them in room messages, logs
or public artifacts. No per-host pairing prompt is required.

Local opaque authentication and queued-device checks use local durable state,
never original-home introspection. The portable grant is valid for 900s; local
opaque credentials remain usable until revoked, membership/realm-invalidated or
explicitly disconnected. Owner/admin credentials, machine proofs and agents cannot
mint human sessions/enrollments through these endpoints.

## Durable private state and revocation

Local credential hashes, immutable provenance and original operation receipts are
committed atomically. A separate service-owned `POCKET_DATA/fleet-credential-key`
derives the same private local credential for original-receipt recovery; no bearer
plaintext is stored in SQLite. The protected key's fingerprint is durably bound
to the database. Key loss/replacement is held, not silent credential rotation.
Fleet/account/node identity is likewise pinned; account replacement requires an
explicit operator migration, not adopting old paired devices into another realm.

`POST /api/fleet/revoke {operationId}` revokes the caller's global device
generation and returns `{version:1,state:"revoked",operationId,ownerDeviceId,
propagation:"eventual"}`. The credential is immediately denied locally. A revoked
credential may ONLY read `GET /api/fleet/revocations/:operationId` for its own
original denial receipt; this restricted receipt read never restores human access.
The existing device disconnect/delete routes also record a durable tombstone
before deleting their original row. Out-of-band original-pair deletion is detected
by the replication cycle and becomes a durable denial, not a new identity.

The runtime automatically catches up every 15s and at startup. It reads enrolled
members' HTTPS `/fleet/v1/revocations/<afterSeq>` endpoints using <=60s node JWTs
bound to exact GET/path/target and `revocation:read`. This service credential can
only read DENIAL facts; importing them cannot create a device/session/enrollment.
Public TLS is verified; no redirects. Streams are realm/node/stream-ID bound,
strictly contiguous, size/page bounded, with atomic durable cursors. Changed or
regressed streams are held for operator review. Accepted denial facts relay through
other members, deduplicate and persist across restart. No tombstone is cleared by
re-enrollment or token refresh. Old-generation replay is refused after catch-up.
Queued human/voice admission checks those same local denials; active device
WebSockets reauthorize locally every 15s, including original legacy pairings.

Revocation across unreachable members is **eventually consistent**. An isolated
enrolled gateway can continue legitimate reads/actions/refresh until it catches
up; a partition can make that interval unbounded. This is not instantaneous
partition revocation, nor a reason to reintroduce an always-online home gateway.
Membership/key changes are read from protected local enrollment configuration;
operator distribution of those changes remains separate from device-denial catch-up.

## Focused evidence

`tests/fleet-enrollment.test.mjs` exercises three independent private databases:
local opaque authentication/refresh with original home unreachable, stable IDs,
original-receipt recovery, restart, distinct-device rejection, eventual partition
revocation, transitive catch-up, durable tombstones and key/realm mismatch holds.

`tests/fleet-independent-gateway.test.mjs` uses two actual owned gateway processes
and a verified local TLS backplane. It stops the original gateway process, then
exercises enrolled reads/actions/portable refresh, lost enrollment receipt lookup,
restart, original command readback after a lost mutation ACK, and automatic
revocation catch-up/restart/no-resurrection. Synthetic upstream mutation count must
remain one; recovery reads the canonical saved command, not a blind resubmission.
These are real software fixture journeys, not a claim of live human or phone use.

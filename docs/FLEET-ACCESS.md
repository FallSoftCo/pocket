# One owner, one fleet connection

## Artifact-only host readers

`artifactReaders: [{id, publicKey}]` is a separate protected configuration role.
It is NOT `members`: these keys cannot issue/authenticate human sessions, appear
in host discovery, query device status, enroll/revoke devices or activate code.
Existing member proofs remain compatible. The distinct
`nextcomp-artifact-reader+jwt` type is accepted only by exact GET/HEAD immutable
blob authorization with node/fleet/account/audience/hash/path/time bindings.
Removing a reader from the current protected config revokes its next request;
no reader key may alias a full member's ID or key.

One-time provisioning uses `scripts/artifact-reader-enrollment.mjs`:

1. On the EXISTING host, create an existing-private state directory and run
   `--action create --state DIR --node ORIGINAL_HOST_UUID --challenge ORIGINAL_REQUEST_UUID`.
   The key stays local. Only a signed public request is returned. Interrupted
   preparation reconciles its original intent/key, never silently rotates it.
2. The actual gateway owner independently verifies the attributable agent/host
   binding and original challenge. Run `--action accept --state PRIVATE_OPERATION_DIR
   --node EXPECTED_HOST_UUID --challenge ORIGINAL_REQUEST_UUID --request PUBLIC_REQUEST
   --request-sha256 ATTRIBUTED_REQUEST_FILE_SHA256 --fleet EXISTING_PROTECTED_GATEWAY_CONFIG`. This preserves member authority,
   records the original config/intent and adds ONLY the read-only role.
3. Return `acceptance.public.json` through the existing trusted owner path. On
   the same host run `--action complete --state ORIGINAL_DIR --acceptance FILE`,
   then the standard bootstrap reader's `--provision-reader` and `--check-reader`.
   The accepted public gateway configuration references the locally retained key;
   no key, human credential, profile or database is transported.

Owner acceptance runs on an existing Linux gateway under util-linux `flock
--no-fork`. The same process retains and verifies the kernel file lock through
config read, original intent, compare and replacement; no spin loop or lock flag
is trusted. It validates the complete reader set, including duplicate public
keys, before writing. Local request creation/completion remains portable. The
acceptance packet must be delivered as a protected owner/current-user file.

Some existing gateways intentionally have a service-owned0600 Fleet config
inside a root-owned0750 directory. The service identity can read but cannot
rename there; ordinary root key/file readers must not silently accept another
UID's private files. For this CONFIG maintenance operation only, root may add
`--config-owner-review ROOT_PRIVATE_REVIEW_JSON`. Its schema is
`nextcomp-artifact-reader-config-owner-review/v1`, with exact `configFile`,
`expectedConfigSha256`, and `file`/`parent` objects containing the independently
reviewed current integer `uid`, `gid`, `mode`, `device`, `inode`. Record numeric
modes, e.g.0600=384 and0750=488; derive actual identities, never copy placeholders.
The parent must remain root-owned and non-group/world-writable. The config must
remain regular, nonsymlink, single-linked0600 with the exact reviewed owner.
The protected review's hash joins the ORIGINAL acceptance intent. Existing
flock, whole-config validation and final content CAS still apply; replacement
preserves the config's owner/mode and the parent's identity. An unchanged
accepted operation reconciles without rewriting. A wrong review or drift holds.

This is not permission to chown/relax the existing directory/config, copy a
service key, broaden `privateNodeFile`, or issue human tokens. Host-side key
creation/completion/read rules do not change. The focused owner integration
requires actual root plus an existing nonroot service UID in an owned `/run`
fixture: it proves service read/parent-write denial, ordinary root-reader denial,
explicit reviewed acceptance and preserved identities without live config reads.

The initial code/bootstrap trust must be owner supplied once, not fabricated
from Hollywood credentials. Subsequent releases use normal scoped reader proofs
without per-release grants. Owner acceptance is a distinct authorized local
operation, not an automatic consequence of a self-signed public request. Gateway
code adoption has its own quiet/rollback boundary; publishing this source or
editing a reader descriptor does not claim the current gateway supports it.

Status: implementation, not a delivered capability. Existing pairings remain valid.

The owner has full access to the enrolled NextComp fleet. Hosts are enrolled by
operators; Android/web users do not pair individually with every host. A tailnet
address is connectivity, not a substitute for the existing device login.

## Identity and transport

A fleet has a stable UUID and owner-account UUID. Each enrolled gateway has a
stable node UUID, HTTPS origin and Ed25519 public key. Its private key stays with
its own backend service. Gateway membership is operator-enrolled; authenticated
clients learn the fleet from their existing connection.

Logical room identity is fleet/account/viewer/authority/room, independent of the
gateway carrying a read. Codex session identity additionally names its execution
node. Credentials, URLs, connection selection and token refresh are not logical
conversation identities. Legacy local profile IDs/cache ownership are preserved
when adopting fleet metadata; account replacement invalidates access explicitly.

## Discovery and owner login

Authenticated GET /api/backends may add `fleet`:

    {version:1,id:<fleet UUID>,accountId:<owner UUID>,nodeId:<this gateway UUID>,
     members:[{id:<node UUID>,name:<label>,origin:<HTTPS base>}],
     sessionPath:"/api/fleet/session"}

POST /api/fleet/session exchanges an actual paired-device login for a fleet
session, or refreshes a valid fleet-device session. Owner/admin/service/agent
credentials cannot mint human sessions. Response:

    {version:1,fleetId,accountId,deviceId,token,expiresAt}

Use standard Ed25519 JWT verification with explicit issuer, audience, algorithm,
type and expiry checks. Human token type is `nextcomp-device+jwt`; its audience
is `nextcomp-fleet:<fleet UUID>`. All enrolled members are authorized destinations
for this owner. Never forward an origin's opaque pairing token to a discovered
host. Discovery and authenticated token exchange must complete before adding a
host to the client's usable pool. Existing paired hosts keep their local device
state; no SQLite/device-database cloning.

Member JWTs cannot cross fleets/accounts. Device revocation and member/key
changes must be durable and propagated; do not label refresh/failover complete
without exercising revocation and restart behavior. Logins/credentials never
appear in public artifacts or room messages.

## Software delivery: service authority is separate

NATS/Hollywood carries manifests, delegation and results. HTTPS carries immutable
blobs. A deployment agent executes only locally and uses a local helper to read
its backend service's protected node key without printing/copying the key.

A node artifact request uses JWT type `nextcomp-node+jwt`, issuer/subject equal
the enrolled source node, audience the serving node, scope `artifact:read`, and
claims `sha256`, `method`, `path`, `iat`, `exp`, `jti`. Lifetime <=60 seconds;
method is GET or HEAD and path is the exact content-addressed blob route. The
receiver verifies the enrolled public key and every scope binding. This proof
must never authenticate human API input. Artifact handlers may additionally
support existing short-lived artifact capabilities, but normal inter-host updates
must not require an operator to copy a new capability for each release.

Private key use occurs inside the local authorized helper/service. Tokens and
headers are not printed by download/deployment receipts. Transfer retries are
reads of the same immutable hash; activation has separate durable operation IDs.
Unknown activation checks the original receipt/current version, never replays a
restart or submission blindly. Verify archive entries before extracting, stage
alongside the current release and retain rollback until health qualification.

## Client acceptance

- Existing authenticated Android/web connection discovers new fleet work without
  Add environment or per-host pairing.
- One combined list; repeated projections of the same room deduplicate by logical
  identity, with truthful host/activity and unavailable states.
- Credential refresh keeps drafts/history/attention ownership stable.
- Every read/write/recording captures its logical target. A reconnect does not
  retarget or replay an unknown outgoing command.
- Native and browser journeys exercise fresh head, request/reply, reconnect,
  gateway outage, token refresh, audience rejection and revocation.
- Publish a compatible signed Android update and the corresponding web build;
  distinguish emulator proof from actual phone installation/voice/push evidence.

## Current implementation checkpoint (2026-10-30)

The server's original per-request home-device lookup is superseded by the local
opaque enrollment contract in `FLEET-SERVER-ENROLLMENT.md`. Enrollment is an
idempotent authentication operation, not permission to replay task commands.
Android preserves every existing pairing and selection; authenticated discovery
adds missing members in a separate lane. Unavailable members back off independently.
The same logical room can retain a newer canonical preview while a different
verified gateway supplies its route and current activity. Its route credentials,
original binding and mutation identity are not replaced with another source's.

The browser gateway enrolls members in the background and retains their opaque
credentials privately. JavaScript receives normal source-qualified catalog data,
not peer tokens. Lost enrollment acknowledgements use the original operation's
receipt, including after gateway restart. Existing explicit browser profiles take
precedence over automatic duplicates. New members attach to the existing socket
fan-in without another browser pairing.

Focused Android model/profile/projection checks and existing browser gateway
integration checks pass. These are implementation foundations, not a claim that
full automatic discovery, cross-gateway conversation draft/history ownership,
web room parity or a signed client release has completed. Those require the next
native/browser journeys against the deployed enrollment candidate. Phone155 and
its announcement remain unchanged at this checkpoint.

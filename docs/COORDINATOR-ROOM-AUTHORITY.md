# Project-room authority and admission (source candidate)

The observed legacy-8bar failure chose an old similarly named room as creation
authority. Its old gateway rejected POST rooms before canonical admission, but
the source wrapper flattened the typed refusal into unknown. This candidate
fixes discovery, selection, preserved refusal and explicit owner reconciliation;
it does not claim deployment or rerun that original command.

## Discover the writer, do not infer it from a title

Existing `/api/hollywood/v3/identity` advertises gateway route support as
`roomControl.protocol=nextcomp-project-rooms/v1`. Existing canonical room catalog
advertises `hollywood-project-rooms/v1`, exact authority/principal, permitted
local `sourceRoomIds` and the actual human creation policy. BOTH must match the
authenticated binding. Gateway support alone is not canonical permission.

`rooms.creation` names supported targets in an unambiguous authority: capable
current environment first, otherwise the sole advertised capable authority.
Multiple unrelated authorities without a capable current source hold explicitly.
Selected Codex focus is never the writer selector. Every creation does fresh
discovery and verifies its source room against that target. Foreign/historical
rooms retain their actual authority even when read through a current gateway.
Read-only legacy sources remain discoverable; missing capabilities are not an
empty inventory and are never permission to probe a POST.

The companion canonical API policy permits an active non-administrator human to
create a project room only from a LOCAL room where that human has both read and
request rights, choosing an active agent with read membership there. It retains
owner and original-human read/post/request membership and normal configured
representative joining. It does NOT promote the human, change an agent's role,
grant host access or admit project work from metadata-only creation. An explicit
create-and-start separately posts the original paired human instruction. Other
administrative operations remain denied. Canonical checks, not client-side actor
lists, enforce this boundary. Existing admin creation is backward compatible.

## Three distinct admission outcomes

- `no_admission`: typed pre-dispatch gateway refusal (including exact legacy
  404 / `HOLLYWOOD_REQUEST_REJECTED` / `Unknown Hollywood human operation.`),
  definitive authentication refusal, or reviewed positive original guard proof.
- Accepted creation/intent: exact canonical read or acceptance, with creation
  and initial task submission recorded separately. Acceptance is NOT completion.
- `inaccessible_unknown`: lost/mismatched acknowledgement or inaccessible
  original read. A later 403/404 never proves original absence. Generic 4xx or a
  post-response binding mismatch is not automatically a no-admission certificate.

An unresolved original creation fences replacements across this device's source
and authority bindings. `roomReceipt` accepts the original metadata command ID
and environment as well as new intent IDs; no blind retry or premature request
for duplicate-risk consent. Unknown messages remain read/reconciliation-only.

## Owner-only repair of the positively rejected old operation

`scripts/reconcile-coordinator-room-rejection.mjs` is a LOCAL owner CLI, not an
HTTP/model tool. It accepts the reviewed original loaded-guard/journal proof,
raw proof SHA and exact device/source/binding/operation/remote row IDs. It checks
original payload/identity, then emits a protected plan with original full-row
digests. A separate explicit apply requires the reviewed plan's raw SHA and fresh
row/proof CAS under an SQLite write transaction.

Run as the EXISTING database service UID, against its protected mode0600 file;
never chown or relax it. Proof/plan are regular owner/root files without group
or world write. Prepare creates only a new plan; apply appends an idempotent
`coordinator_room_reconciliations` overlay. It changes NO original room-command,
transport, voice-call or conversation row and dispatches NO command. Later drift
of an original row invalidates the overlay and holds again. A 403 readback is not
acceptable proof. This relies on an explicitly trusted local owner's positive
source/journal review, not an independently signed canonical absence receipt.

```sh
node scripts/reconcile-coordinator-room-rejection.mjs --action=prepare \
  --database=ORIGINAL_DB --proof=REVIEWED_PROOF --proof-sha256=RAW_PROOF_SHA \
  --device=ORIGINAL_DEVICE --source=ORIGINAL_ENVIRONMENT \
  --binding=ORIGINAL_BINDING --operation=ORIGINAL_OPERATION \
  --remote-command=ORIGINAL_REMOTE_ROW --output=NEW_PRIVATE_PLAN
# Review the exact plan and retained evidence before this separate action.
node scripts/reconcile-coordinator-room-rejection.mjs --action=apply \
  --database=ORIGINAL_DB --proof=REVIEWED_PROOF --proof-sha256=RAW_PROOF_SHA \
  --plan=NEW_PRIVATE_PLAN --plan-sha256=RAW_REVIEWED_PLAN_SHA
```

This has been exercised only on isolated synthetic owner fixtures. Production
repair, source release and any later explicitly authorized creation remain
separate owner actions. Do not reuse a rejected operation with changed payload.

## Compatible release and rollback

Pocket changes are additive server/tool source, no dependency/lock, Android,
pairing, profile or model update. The coordinator's supported idle contract
migration adopts revision5; active turns and saved history are not replaced.
API changes are independent API-only code, optional `sourceRoomId`, no schema or
dependency migration. Existing admin wire calls and receipts remain compatible.
Deploy canonical support before advertising a usable non-admin creation path;
either missing side fails closed. Root owns exact local-manager quiet activation.

Rollback uses retained program closures and original definitions. Removing API
support removes its catalog capability and new creation fails closed. Rollback
does not erase accepted rooms/memberships, resend unknown commands, rewrite old
receipts or undo the appended positive rejection audit. Keep additive SQLite
tables and original receipts. An old coordinator must not be allowed to bypass
unresolved originals merely because a newer reader has diagnosed them.

## Evidence boundaries

Focused tests include actual legacy/current authority selection, exact old
gateway typed refusal, cross-source holds, reopen/dedup and real local repair
CLI with wrong-plan refusal and immutable original rows. The isolated integrated
HTTP/PostgreSQL journey exercises a non-admin human, canonical policy negatives,
newest conversation plus preceding cursor, creation and message lost ACKs, and
private-scope enforcement. Model/native execution and physical HTTPS/DNS are
stubbed; there is no production or handset qualification claim. All unsuccessful
attempts and source/command receipts belong in the owning release record.

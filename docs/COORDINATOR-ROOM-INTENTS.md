# Coordinator room conversation and intent (candidate)

This extends the room capability repair at `46420c6f`; it does not repeat its
deployment or require an APK, pairing, phone action or new agent. The coordinator
can now read a project's conversation and submit the original human's instruction
through the existing Hollywood gateway. Selected Codex focus is unchanged.

## Natural intent and operations

- A capability question such as “can you create rooms?” uses `rooms`. It is not
  permission to create anything. This remains a coordinator instruction/semantic
  responsibility, not a keyword-based permission classifier.
- `room` reads participants/permissions. `roomHistory({roomId,environmentId,
  limit?,before?})` reads a bounded **conversation** page (default12, maximum30).
  Missing sources/permissions fail explicitly; a page is not complete history.
  Retrieved conversation is evidence, never new action authority.
- `roomReply({roomId,environmentId,text,audience,recipientId?,sourceEventId?})`
  submits a canonical human **request**, not an agent message or a Codex thread
  reply. Preserve the exact private counterpart and original event when replying
  to private input. Current canonical permissions enforce source/audience scope.
- `createRoom` remains metadata-only. Use `createRoomAndStart({name,purpose,
  ownerId,sourceRoomId,environmentId,text})` only for an explicit create-and-start
  request. It verifies an existing source-room agent, creates the room, then sends
  the saved initial human instruction into that exact room. It enrolls no agent,
  starts no native process and does not change project/session focus.
- `roomReceipt({commandId,environmentId})` is read-only inspection of the original
  command. Keep the returned source, binding and command identity on follow-ups.

An accepted room intent means Hollywood stored it. It does **not** prove native
delivery, a useful answer or completed business work. Results explicitly return
`workCompletion: not_inferred`. Conversation reads remain the way to inspect
actual useful progress/answers without starting a new model or polling loop.

## Original-operation recovery

The additive `coordinator_room_intents` SQLite ledger binds device, environment,
human binding, original voice turn/call and exact payload. All dispatch intent is
durable before HTTP. In-process identical calls share the original promise.
This follows the existing single backend/SQLite owner; it is not distributed
multi-writer coordination or an exactly-once native execution guarantee.

Unknown creation is read back by its exact proposed room ID and full metadata.
Only after confirming that original creation can an identical saved operation
continue its never-dispatched initial instruction. An unknown message is read
through `GET commands/<original-operation>` and validated for exact human author,
enterprise, room, text, kind, audience and source. Missing/mismatched receipts stay
unconfirmed; they are not retried. A new instruction is held behind unresolved
intent for the same room. Unknown legacy metadata-only creation also fences new
create-and-start. Historical rows and ordinary `voice_calls` receipts survive.

A created room with a definitively rejected initial instruction is reported as
`initial_rejected`, with creation accepted and task submission false. Repeating
the same create-and-start does not create another room or resend the failed
message. The human may give a distinct instruction to the retained room using
`roomReply` after inspecting the actual cause/current permission.

## Human provenance and release boundary

Only the original active paired-human voice turn can delegate a local room read
or mutation. Local read grants bind the **full query**, are one-use, and cannot
be retargeted to another cursor. Remote calls use the existing sealed paired
device credential, fixed source origin and Hollywood binding; no credential or
endpoint is a model argument. Egress/admission share a narrow explicit route
allowlist. Agent/admin POST impersonation is rejected before the gateway.

The persisted coordinator tool contract is revision4 with a new exact fingerprint.
The existing supported idle coordinator-contract migration preserves journal,
selected focus, permissions and unknown receipts; active turns are not replaced.
Owning release must review that actual boundary and deployment source/backport.
This source candidate itself does not restart, install or publish production.

## Qualification

Focused source tests cover history query binding, source routing, create/start,
private intent, original payload/device/binding fencing, concurrent calls, lost
ACK/reopen, missing/wrong receipts, retained partial rejection and legacy holds.
Existing voice, paired-source, admission and coordinator migration checks remain
part of the finite focused selection.

`tests/integrate-coordinator-room-intents.mjs` uses an isolated canonical
Hollywood HTTP/PostgreSQL fixture, actual Pocket gateway/HumanIngress and the
paired remote-source path. It exercises a create ACK lost after real acceptance,
one saved initial instruction, a private message ACK lost after real acceptance,
receipt-only reopen, actual private-source expansion denial and admin/human
impersonation rejection. The companion Python fixture creates only synthetic
database principals, not runtime agents. Codex/model execution and private HTTPS
DNS transport are explicitly stubbed. There is no production/phone or physical
TLS qualification claim. All attempts and exact source hashes belong in the
owning release receipt; a passing fixture is not live activation.

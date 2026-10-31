# Browser room surface candidate (2026-10-31)

Based on `ac8855b7e5366cc86ee1756062403d1eb23c0b0e`. This is isolated
implementation, not deployment, browser-device, phone or provider-audio proof.
The source coordinator owns gateway/runtime integration and release.

## Mounted surface and identity

`web/app.mjs` mounts `RoomClient` and the existing `RoomHistory` into the actual
mixed work list, reader, review pane and bottom composer. Room IDs are logical
authority + human + room identities, not stock thread IDs. Explicit gateways
select an existing authenticated browser environment; no credential is exposed
or invented. `X-Hollywood-Binding` and the returned installation/authority/human
envelope must match every room call. Scope checks also apply before history is
mutated. Room messages retain their human/agent and public/private attribution;
native actions remain condensed operations, with terminal-wins state and exact
native provenance in expanded details. Audience boundaries separate Run groups.

Initial HTTP history explicitly requests `before=9007199254740991` (the API's
cursorless default returns oldest). Each projection owns independent events,
older cursor and live head. Subsequent reads use the last canonical sequence;
a bounded tail/gap lane keeps latest results reachable through large backlogs.
Older loaded rows remain in memory across head reads. Native observations, not
historical executor receipts, drive room state. Expired/malformed observations
do not assert Idle. A failed detail read does not discard a successful message
page; it withdraws current posting/status assertions.

The latency review adds singleflight discovery, a three-source concurrent first
wave, 60-second per-source identity caching (every returned binding is still
checked), and a 15-second catalog refresh floor. One verified Hollywood ingress
already federates its rooms: discovery does not scan every fleet member on every
read. A healthy source publishes immediately while unrelated probes continue;
cached navigation remains available. New roster epochs abort prior requests and
discard late results, even if a transport ignores cancellation. GETs have a
four-second bound; a discovery pass has a ten-second overall deadline. Sources
are retained as unavailable on failure, rather than deleting their saved scope.

Read-only history/detail/attention GETs hedge across at most three already
verified routes for the same authority/human/room. First valid bound response
wins; losing reads are cancelled. Selected physical write/draft routes and
original uncertain receipts do NOT fail over. Successful head content renders
before independent metadata/attention reads finish. Slow merged stock inventory
does not prevent incremental room opening or the existing active-head lane.

`data-room-id`, `select[data-room-view]`, `select[data-room-gateway]`,
`select[data-room-recipient]`, `select[data-room-audience]` and
`[data-room-action-id]` are mounted acceptance selectors.

## Drafts and uncertain delivery

Room drafts retain browser pairing, physical environment/binding, authority,
human, room, recipient, audience and source event. Explicit gateway/target
navigation saves the original draft and opens a separate draft; it never moves
a saved outgoing request. Public human input fans out by default; private input
requires an explicit recipient. Server permission checks remain authoritative.

All typed sends save one original ID/body before dispatch, mark dispatch before
network mutation, then inspect that original receipt. HTTP200 alone cannot
acknowledge delivery. Stock receipt ID/thread/state, coordinator ID/text/state,
or room binding/event/author/body/audience/target/source/attestation must match.
Unknown, missing, failed or mismatched receipts retain draft and original state;
recovery never POSTs again. A failed original history refresh also retains them.
A durable accepted receipt precedes local cleanup. Draft revision checks prevent
a delayed acknowledgement from clearing a newer edit, even identical retyping.
Legacy journals without a revision keep their draft conservatively.

Native questions/approvals capture their complete original native scope and
response. Both delivery/generation and conversationVersion1 callbacks are
recognized. Current scope and callback availability are rechecked; expired or
changed questions cannot become new work. A dispatched response only reads its
original attention command receipt; unresolved receipts remain inspectable if
the callback leaves the current list. Acceptance and native consumption differ.

## Audio and existing controls

Stock/coordinator recording routes are retained. A room recording requires an
explicit agent supported by the existing canonical audio contract; all-agent
fanout voice is explicitly unavailable. Destination and physical transport are
captured before recording and retained in its journal. Playback connections
also retain their source rather than following session navigation. Starting or
reopening an unknown recording does not replay a task. These source changes do
not qualify real browser microphone permission, WebRTC, provider finalization,
speech quality, device volume or physical-phone behavior.

## Gateway integration boundary and evidence

The frontend accepts an authenticated `GET /web/environments` roster with
`{environments:[{id,name,available}]}` using existing gateway profile IDs only.
404 falls back to the original local gateway; it does not guess fleet IDs.
The source coordinator must qualify roster support on native-only gateways,
where `/api/threads` is intentionally forbidden. Existing merged stock catalogs
remain usable. The gateway must also retain its newer conversationVersion1
attention validator; this candidate does not modify server/runtime ownership.

The original checkpoint `a7d91f2` passed 31 independent fixture/mounted cases
(25 virtual-DOM journeys and 6 protocol checks), including a retained failing
Enter control on `95d2453`. Original missing-mount/receipt RED logs remain intact.
That result does not automatically qualify this later latency revision.

The latency revision's focused room model/surface/discovery and actual local
HTTP receipt-forwarding files pass 14 cases under CPU storage admission. The
HTTP fixture confirms stock POST returns only `{id,state}`; original GET returns
`{id,thread_id,state,result}`, with the gateway qualifying `thread_id` but leaving
opaque `result` untouched. POST alone cannot acknowledge delivery. Unknown,
wrong thread or missing GET receipts fail closed without another POST.
Independent delayed-source/failover mounted acceptance, rendered browser, real
federated transport, native audio and phone gates must be reported separately
before release. No gateway restart, signing, publication, new pairing or phone
install is performed by this candidate.

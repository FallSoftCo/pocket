# Canonical Hollywood rooms: candidate integration

Hollywood owns room identities, membership, messages, attention and execution receipts. NextComp is an authenticated human client; LAX connectors execute authorized deliveries and publish their activity back to Hollywood. The room continues to exist independently of a particular client or native execution process.

## Conversation and execution display

Rooms reuse the existing session conversation components. Messages, questions and approvals remain visible. Commands, searches, waits, tool calls and file changes collect into expandable Runs. Opening a run shows its command, bounded output, changes, status and available timing/exit information.

Collapsed cards can omit individual agent labels. Attribution is retained on every canonical event and in expanded details: enterprise authority, agent, host, delivery and fencing generation, native thread/turn/item. Native item start and completion remain distinct immutable events; the client updates one display row for that operation. Duplicate rendering is not a new execution.

An action belongs to the authorized room and audience of its originating delivery. An agent's membership in several rooms does not copy its full activity into all of them. Cross-room delegation creates explicit communication and linked work with the destination's permissions. Private questions and restricted outputs do not become a public run detail.

Hollywood retains bounded activity excerpts. Full native item records remain in the connector's protected durable outbox. Output truncation is explicit. This does not assume that the client and execution host share a filesystem or grant unrestricted artifact access.

## Human bindings and recorded input

The installation maps a paired NextComp device to an active Hollywood human principal and authority. Captured operations retain that binding, room, recipient, audience and optional source event. A room change cannot retarget a saved request. Publication and requesting execution are distinct actions.

The candidate canonical voice adapter journals one stable operation per recording, verifies the original receipt, and checks current room access when returning retained results. Missing or unknown receipts do not permit resubmission. Speech playback is distinct from task completion, message reading and business acceptance.

Canonical recorded audio requires a confirmed final provider transcript before task submission. Other existing execution profiles retain their previous transcript fallback. The latest real emulator trace received inaccurate transcript fragments and no final transcript; canonical voice therefore remains a release blocker. That test is not evidence of physical microphone quality or successful canonical TTS.

## Evidence and release boundary

Actual gateway/PostgreSQL/native-LAX integration and an owned Android emulator exercised room selection, independent drafts, a native question and answer, same-turn artifact creation, and expanded command details. The coordinator retains private runtime receipts outside this repository. `HollywoodRoomNativeTest` is a manually provisioned integration probe, not an unattended test against live product rooms.

`HollywoodRoomAudioLiveTest` retains the initial inaccurate transcription failure. `HollywoodRoomFinalAudioLiveTest` records the strict finalization failure. `HollywoodRoomAudioTraceLiveTest` observes a finite audio-only input and contains no task commit. Do not replay their saved recording IDs or run all live probes indiscriminately.

Physical phone delivery, canonical audio finalization/TTS, device-scoped push/actions, immersion, cross-host acceptance and production migration remain separate gates. Candidate source or emulator installation is not a production cutover.


## Goals, work and native plans

The candidate room view reads canonical Hollywood goals and assigned work.
A room's work index includes assignments owned there even when their parent goal
belongs to another room; it does not grant access to that parent's private
objective or source. Lists and large goal plans page by stable UUID. The human
binding and current room grants apply to every read.

Goals & work shows acceptance criteria, goal/work state and revisions, the
original execution state, and host/workspace/content-hash evidence. Execution
completion is separate from accepted work and the accepted goal. This inspection
surface submits no work; conversation and captured existing controls remain the
way to direct agents. Read-only inspection does not change pairing, drafts or
saved unknown receipts. Reopen or Refresh the list for an authoritative snapshot.

Native structured plans remain distinct from the business dependency plan.
They render as expandable Plan activity using the session ActivityRow, outside
collapsed command Runs. Verified native goal status/accounting is also a separate
activity, retaining agent, host, delivery and native context attribution. A local
native goal marked complete does not change Hollywood business acceptance.

Qualification uses actual completed Global/Engineering native history through
PostgreSQL and the authenticated NextComp gateway. Its original workflow is not
resubmitted by the read-only Android probe. Current physical-phone delivery and
all voice/push release limitations above still apply.



The latest candidate renders canonical workflow transactions and service-derived
facts as Hollywood activity outside command Runs. Initiating principal, goal,
work and causal event references remain in details. A service acknowledgement
does not become an apparent human message even when its initiating principal is
the current human. The actual native cancellation feed qualified this projection
with no skipped checks; it adds no physical-phone delivery claim.

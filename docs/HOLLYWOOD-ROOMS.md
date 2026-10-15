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


## Guide and Stop active work

Alpha100/code118 adds explicit Guide and Stop actions alongside active room
execution. Ordinary Send continues to create the explicitly addressed room
request; Guide targets the captured active work. The human does not create a
competing Global merely to redirect its current task. A running command may
finish before the model handles guidance.

Guidance has a separate saved draft and original operation, bound to the source
profile/human, room, audience, delivery, host generation and native turn. Saved
or uncertain actions never follow navigation or silently become a new task.
After an unconfirmed submission the client reads the original control receipt.
Unresolved guidance remains reachable after its active execution leaves the
room list. Ended work cannot accept new guidance through the dialog.

The UI distinguishes locally saved, saved by Hollywood, native admission and
recorded native history. None means that an artifact was accepted or that a
human saw a response. Authorization is enforced by Hollywood again at native
dispatch; the Android control is not an administrative permission grant.

The alpha100 Android candidate compiles. Its Guide dialog has not yet had native
Android UI qualification or physical-phone delivery. Actual native/gateway
execution and failure recovery are recorded separately in the versioned
qualification. Alpha99's prior actual emulator question/notification/offline
reply proof remains specific to that journey and WebSocket notification
transport; genuine FCM and native voice remain release gates.

The finite actual native/gateway guidance run passed: one original control,
four real native turns including a separate explicit cancellation, worker artifact
and independent Global acceptance. After the owned processes stopped, a
metadata-only read against the original native store found exactly one matching
clientId message in the original completed turn. A fresh alternate native SQLite
store returned metadata with no turns; that is not proof of missing work.
Preserve actual native storage and its identity during backup/migration.


## Alpha101 guidance recovery qualification

An actual native Android Guide journey reopened a typed guidance draft, sent
it to the original active turn, preserved room/viewer drafts and rejected a
foreign-profile intent. After the owned connector stopped, metadata recovery
retained the original native message and artifact with zero additional model
turns. Android read the original receipt after work ended and could not send
new guidance to that ended execution. Canonical room control events also open
a read-only current receipt, including an explicitly unknown outcome, without
requiring a local outgoing intent.

Unsent guidance drafts now retain their scope and remain discoverable after
work ends; ended drafts remain readable/selectable without becoming new work.
The native journey exercised reopening the draft while active and opening a
saved pending receipt after completion. Physical phone, native voice, genuine
FCM and the wider enterprise release gates remain unqualified. Read the exact
[versioned evidence](qualification/native-guidance-recovery-alpha101.json).

## Room catalog activity (follow-up candidate)

The additive `room.activity` contract is a viewer-authorized snapshot from one
batched Hollywood catalog query. Version 1 requires `version: 1`, `observedAt`
(Unix epoch milliseconds), and nonnegative safe-integer counts for `pending`,
`claimed`, `running`, `cancelRequested`, `unknown`, and `waitingForHuman`.
Attention counts use viewer-visible pending requests from the current delivery
generation/claim; direct-audience permissions match room detail. Counts overlap,
so NextComp displays categories separately and never adds them into a total.

A validated all-zero snapshot shows Idle: no visible unsettled work at that
observation. This says nothing about host connectivity, agent health or completed
work. Missing, unsupported, malformed or unavailable data shows Status unavailable.
A failed catalog refresh retains navigable rooms while withdrawing their status
assertion. `observedAt` is not work recency and never changes ordering.

The Android follow-up uses the existing catalog lane (15 seconds plus room-list
entry/reconnect). It performs no per-room history scans or model calls to derive
presence. Native room cards keep inline immersion for common labels and room
purpose, and expose all counts in accessibility text. Alpha102 stays immutable;
this follow-up has not been published or installed on the phone.

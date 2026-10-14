# Hollywood room execution display

Rooms belong to Hollywood. NextComp uses the same `commandRunProjection`,
`CommandRunsHeader` and `ActivityRow` as native session conversations.

LAX observes native items and saves immutable publication intent plus the full
item in its protected filesystem execution journal. A background outbox sends
bounded typed records to Hollywood, independently of native control handling.
Reconnecting republishes only the original log identity, never an execution.
Hollywood atomically stores the correlated room event and receipt. Activity
cannot create a delivery or wake an observing agent.

Each record captures authority, original request, delivery, generation, agent,
host, native thread, turn, item and phase. Room and audience come from the admitted
delivery. A direct execution's log remains direct; question tool activity is
private to the attributable human. Raw read-tool payloads can contain another
room's private history, so they stay in the protected execution journal. Public
room records retain action identity/status and native-item digest. Output/diff
excerpts are explicitly marked; this is not an authorized artifact retrieval API.

Both start and completion remain in immutable history. The Android projection
updates one stable operation row at its original position. Its identity includes
the execution and generation; equal item IDs in other contexts cannot merge.
UUID event identities and sequence cursors remain distinct. Consecutive activity
in the same execution collapses into the existing Runs header. Messages,
questions, approvals and context boundaries remain outside it. Failures and
unknown outcomes remain visible when collapsed. Expanding shows commands,
output, diffs, exit status, duration and agent/host/context attribution.

This candidate projection accepts canonical v3 events alongside the frozen room
renderer. It does not change Android pairing/profile/draft state or establish a
production v3 connection. The gateway/client routing, physical-phone controls,
voice/push and production migration are separate outstanding release gates.

Qualification uses actual PostgreSQL/HTTP, API restart and one finite native LAX
question/command turn. `RoomActivityNativeFeedTest` receives its private real room
history through `HOLLYWOOD_NATIVE_HISTORY`; no deployment captures are committed.
The projection test is not physical Android or screenshot evidence.

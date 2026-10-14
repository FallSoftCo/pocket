# Native Hollywood gateway bindings

Hollywood owns rooms, audiences, permissions, events, execution deliveries and
attention. NextComp is an authenticated client. The gateway forwards a narrow
human API; it never resolves recipients from membership or claims agent work.

Installation writes protected `hollywood.json` with format
`hollywood-nextcomp-binding/v1`, a durable installation UUID and a `devices`
map keyed by existing NextComp device UUID. Each entry names the fixed private
Hollywood URL, protected token file, authority UUID, human principal UUID and
authorized default room UUID. An optional default entry represents one owner's
paired devices. Tokens never enter Android or model-selected endpoints.

The binding ID derives from installation, enterprise and human identity. Android
must capture it with each draft, request and approval and send
`X-Hollywood-Binding`. A different binding rejects before forwarding. Credential
rotation retains identity. API verification requires the registered active human
and accessible default room. Hollywood rechecks authentication and permissions
on every operation. A device cannot borrow another device's principal binding.

Native mode is the default for this registration. Deployments also set
`POCKET_EXECUTION_BACKEND=hollywood` to remain closed if registration is missing
or broken. Native mode exposes LAX as the backend, blocks the old single-owner
session/notification/control routes and parks stock discovery/admission/recovery
schedulers. Existing pairing and queue rows are retained. Other environments
without this registration retain their own existing profiles and behavior.

Writes keep stable operation IDs. Unknown replies are retained, not interpreted
as rejection or automatically resent. Message outcomes are read by original
command ID. Attention response outcomes are read by the exact attention and
principal-scoped command ID. Scope captures authority, human, room, delivery,
agent, host generation, native thread/turn and action digest. Response accepted,
written into native RPC and execution completed are distinct facts.

LAX command/file approvals capture the full native action context, including
callback identity and environment/network context. Distinct callbacks for one
item keep distinct slots. Worker approval policy is explicit configuration;
Global's actual administrative capability remains independently mandatory.
Approval changes the sandbox decision, not the worker's OS UID or sudo rights.

Actual qualification used the real NextComp server and PostgreSQL/Hollywood
with two disposable authenticated human fixtures. A native LAX context received
an exact command approval across gateway restart and wrote the expected file.
A separate boundary scenario observes retained queue fixtures against a runtime
connection trap and checks exact response receipts without replaying execution.
The first boundary attempt failed before gateway launch because its Unix socket
path exceeded Linux's limit; its original queue fixtures and receipts were
preserved for the bounded observation resume.

This is candidate server evidence. Canonical Android navigation/drafts/control
UI, native voice/immersion/push adapters, physical-phone qualification, legacy
identity/state import, host enrollment and production cutover remain release
gates. Native mode deliberately does not expose unsafe old routes to fill these
gaps. No current production defaults, phone pairing or APK were changed.


## Canonical Android room controls

`HollywoodClient` captures the paired endpoint, credential and installation/human
binding before dispatch. Drafts and pending intents include the room, explicit
recipient, audience, source event and communication kind. Navigation cannot
retarget an in-flight action. Saved unknown messages and attention answers use
read-only lookup of their original operation receipt. Questions and approvals
remain tied to their original scope/action digest; accepted submission is not
shown as proof of native consumption or completed work.

The native room screen reuses session cards, conversation surfaces, composer and
condensed Runs renderer. It defaults to the installation's authorized room,
exposes explicit recipients/private scope and publication without work admission,
and cancels exact authorized delivery IDs. Room actor names, permissions and
active visible work come from Hollywood's room-detail endpoint; no client-owned
membership graph or guessed shared filesystem is introduced.

History initially loads the latest bounded page, then advances the authoritative
forward cursor. Earlier pages load separately. Private filtering happens at the
service for both directions. Manual reading disables automatic following. The
Android projection preserves UUID identities and native execution attribution.
Native mode avoids the old stock catalog/reply readers and uses Hollywood status
for its authenticated transport handshake.

A fresh bounded Android 35 emulator on OZZZ-OS ran against actual NextComp and
PostgreSQL/Hollywood. It loaded a 125-message history, retained independent room
drafts, submitted an explicit request, answered a real native LAX question, and
expanded the resulting command. The same native turn wrote the exact answer to
the workspace proof file. First-run notification setup, loopback routing and
accessibility-tag failures were reconciled before the request was admitted.
The worker, gateway and emulator were stopped after the finite proof. This is
emulator evidence, not physical-phone, native voice/push or production cutover.

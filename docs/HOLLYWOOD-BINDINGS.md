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

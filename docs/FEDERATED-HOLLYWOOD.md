# Federated Hollywood access

A native development environment is additive. It must not replace the current
Codex environment, copy an existing device database or change an enterprise
human binding. A dedicated paired human sees only explicitly enrolled rooms.
The same room identifiers and existing room APIs reach their canonical node.
Agents retain agent authorship and native execution stays on its assigned host.

Notification ingestion discovers source nodes from the authenticated catalog.
The existing inbox API accepts a nodeId query; every node retains its own
canonical sequence. NextComp persists cursors per device, binding and node.
Existing local cursors migrate without replaying old room history. Pending
questions are recovered independently. Peer outage preserves its cursor while
local updates continue; restart reads the missing peer from its retained cursor.
Current audience and question state are checked again before provider delivery.
No message text, credentials or approval command is placed into FCM.

This is deterministic public-state ingestion, not model polling or worker
continuation. Room actions remain separate from chat and do not create pushes.
No new HTTP endpoint is required. A discovered organization without permission
is not a host outage. A room's canonical owner can be unavailable; this does not
claim automatic room-state replication or failover of native model context.

The focused SQLite integration checks equal counters on different nodes,
continued local delivery during peer loss, restart recovery without duplicates,
and preservation of existing local cursors. Physical transport/native-client
qualification must be recorded separately before claiming phone delivery.

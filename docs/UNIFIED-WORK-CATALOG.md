# Unified work catalog

The work list is independent of the selected development environment. Every row
retains its source pairing, backend, query scope, authority, kind and canonical ID.
Environment selection chooses where new work runs; opening an existing row uses
that row's captured source. Room and stock-session symbols remain distinct.

## Canonical ownership

CodexCatalog owns one CodexCatalogStore per paired source and recent/archive query.
The selected-profile API is a compatibility projection of those stores. The merged
list and conversation invalidation use the same state and HTTP admission. Opening
another environment retires its selected read admission without erasing its
canonical catalog, retained history pages or cursor. Live selected-source events
update the merged projection directly; foreign source reads are independently
bounded. Observation time is freshness, never work recency.

Foreground discovery checks all saved pairings. A slow source cannot hold back a
healthy source. Catalog reads remain single-flight in their source owner; the
bounded list poller joins them. Search and older-page reads use each source's
existing independent query lane. Archive scope has a separate key and cursor.
Reads stop when the list leaves the foreground. These are metadata reads, not
worker turns, continuation triggers or model polling.

Rows retain their relative positions while the user is interacting. Normal work
recency and the existing active-session tie policy govern later order changes.
Android lazy item keys use a length-prefixed String encoding of the full identity,
rather than a non-saveable object or an ambiguous server ID.

## Source-bound controls

WorkSourceActions revalidates the captured pairing, backend, binding and authority
at admission. Rename/watch/restore use the original source without changing the
selected profile. This metadata path rejects reply/turn operations. Opening a room
uses the typed room destination, including keyboard intent, without manufacturing
a notification or message. A late backend/default response cannot override an
explicit backend choice.

Existing transcript repositories, drafts and durable outgoing receipts retain
their original environment identity. Discovery does not move sessions, resume
workers or replay unknown commands.

## Evidence and remaining delivery

The Codex work screen is wired to the merged renderer in the candidate. Focused
native emulator journeys passed cross-source discovery and one-tap navigation
between identical IDs, preserved source drafts and unknown delivery, startup
loading/retry, independent catalog freshness and paging. These are synthetic
native integration results, not a phone installation or proof of every host.

The new signed release is being qualified. The installed version is still the
previous release. The Mac stock daemon and authenticated gateway were separately
verified through native Unix and private HTTPS metadata reads without model turns;
that does not mean the phone has paired with the Mac yet.

Room cards can appear with their captured source alongside sessions. The retained
native room-only Back/list route still needs consolidation. The current room
catalog provides no meaningful work timestamp or latest action preview: it must
not use activity.observedAt, room edits or inventory polling as fake recency.
Foreign-source push setup and browser parity remain separate delivery work.

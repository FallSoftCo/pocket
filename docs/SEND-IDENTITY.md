# One send, one visible message

The transcript, client intent journal and server outgoing receipt are distinct evidence sources for one logical send. Their reconciliation key is a verified client send identity within an environment, thread and turn. Text equality, proximity and refresh timestamps are not identity.

Provider history may omit a client ID that was present in an earlier item event. In-memory retention alone cannot survive a backend restart or timeline eviction. The backend now retains provider-issued item/client pairs in its private SQLite database. Event ingress records the pair even when a thread is not open; bounded history reads restore it before timeline projection and position indexing. This creates no model calls or work activation.

The ledger stores IDs only, scoped by thread and turn in the environment's existing private database. Identical text from different sends stays distinct. Conflicting claims do not enrich an unproven row. Metadata-poor items with no prior verified evidence remain unlinked. Unknown delivery remains unknown: rendering never authorizes another send or silently marks an uncertain receipt accepted.

Qualification includes a real database close/reopen, metadata-poor history and events, compact projection, environment/thread/turn isolation, and conflicting claims. Native synthetic Android verification checks one visible body with recovery controls and two bodies for an independent identical input, without another dispatch. See the versioned receipt. These checks do not prove the user's installed cache has been repaired.

Existing legacy rows without a stable provider relationship cannot safely be collapsed using their text. Physical cache inspection and the installed-version check remain necessary to establish the reported duplicate's exact cause. No transcript deletion or blind send retry is part of this change.

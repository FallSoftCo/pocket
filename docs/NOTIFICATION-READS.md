# Reading a conversation clears its update notifications

A fresh conversation fetch supplies a read cursor, but fetching does not acknowledge it. Android sends the acknowledgement only when the resumed, unfiltered conversation has settled at the latest material row for 500 milliseconds. Reading older history, the Important filter, active voice mode, blackout and background fetches preserve unseen updates. A cached reopening waits for a successful current read. The cursor is the highest notification ID returned for that conversation. A failed fetch or acknowledgement never removes tray notifications. This is a presentation watermark, not proof that every earlier passage was comprehended.

The server stores read cursors per authenticated device and conversation. Reading on one device never consumes another device's notifications. Cursors advance monotonically and must identify an existing notification from the named conversation. Unresolved questions, approvals, and errors remain actionable until their attention state is resolved.

Android records acknowledged cursors under a hash of the originating server URL and pairing credential, preventing unrelated local/workstation profiles or replacement pairings from sharing read state. It clears only IDs explicitly returned by the server. Later deliveries of already-read ordinary updates are suppressed from both the tray and speech. Already-playing or deliberately queued speech is not interrupted merely by opening a conversation.

Backend tests cover device/conversation isolation, unresolved attention, resolved attention, cursor validation, and monotonic advancement. Android policy tests cover unresolved/unknown attention and updates beyond the fetched cursor.

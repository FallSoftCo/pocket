# Codex conversation synchronization

The current repair is scoped to stock Codex conversations. Hollywood-specific UI work is deferred; existing native rooms remain supported.

`PocketTranscript` retains the canonical identity/order/cache reconciliation and owns one `TranscriptReadState` per current view. The read state admits one latest request independently of one historical request. Older-page and gap requests share the historical lane because they advance related coverage boundaries. Tickets retire only their own lane; navigation/reset invalidates old tickets and generation checks discard late responses. Every request captures its original environment before admission.

Only latest reads own current-head freshness, retry eligibility, refresh intent and the buffer used to reconcile streamed events with snapshots. Historical reads neither drain that buffer nor clear a pending latest refresh. Latest errors, historical errors and unresolved positioning failures remain separate. An older-page failure cannot disable current-head recovery, and its successful completion cannot erase a current-read error.

Current and historical pages merge using the existing canonical item/client identity evidence and version/order rules. No role-based sorting, timestamp guessing or synthetic user IDs are introduced. Historical coverage remains local and readable. A gap response whose cursor was superseded by a newer latest read is discarded; the current gap can then proceed. Request admission never sends, resumes or retries model work.

The native held-page journey is a regression test of this ownership boundary: an older result is held at publication, canonical fixture content advances, latest loading must paint the new answer before the old result is released, then reopening must verify authoritative history. The journey also checks duplicate count, retained draft and zero task dispatch. Separate existing native journeys exercise same-turn gap answers and navigation during a blocked prior read. This does not by itself qualify the physical handset or every cause of stale messages.

This is the first bounded migration toward repository-owned conversation lifecycle. Selection and catalog still reside in Pocket, and UI pacing/input/rendering remain later work. Do not describe the entire architecture repair as complete because this admission boundary passes.

The foreground live-transport owner also awaits a selected-Codex status-head probe every five seconds. Opening a conversation no longer removes its periodic invalidation recovery when the list composable unmounts. This is a bounded authenticated metadata GET, not a model/history/catalog polling loop. Only an advancing verified head requests history. Stopping/backgrounding the owner cancels publication; the probe captures environment/thread identity, never updates pairing/push registration, and cannot change command delivery state. Equal heads do no transcript work. Existing full status refreshes remain separate.

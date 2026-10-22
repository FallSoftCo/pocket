# Codex transcript authority

Status: implementation required. Android149's follow-window correction is containment, not completion of this design.

## Observed production contract

On 2026-10-22 UTC, read-only calls to the existing signed-in native daemons returned thread/timeline/list for both the affected MacBook session and NextComp Dev on system76. Eight entries returned in5ms and4ms respectively. Responses contain canonical rollout position, item/turn identity, nextCursor, and activeRealtimeSessionAtPageStart. No thread/start, turn/start, resume, permission or account change was used. Source inspection shows bounded indexed SQL over native rollout ordinals; this method does not need our manual turn/item chronology reconstruction.

The maintained native source exposes timeline entries in canonical rollout order. The TUI consumes its native app-server event stream. Its implementation is evidence of the native contract, not proof that our installed Android client follows it.

## Existing failure

NextComp currently reads separate turn/item pages, maps them into server timeline rows, then independently merges shared message-cache windows and the screen's retained history. Canonical native timeline positions are absent from this path. Provider aliases, arrival ordering, missing intervals and scroll anchors are subsequently repaired in several places.

The Mac gateway returned a newer response in23ms while installed Android148 showed older messages. A concrete screen defect used a measured saved anchor to select an old disconnected component even with follow=true. Fixing that rule does not eliminate the competing transcript authorities.

## Required ownership

1. A thin authenticated environment adapter forwards bounded native timeline pages, preserving positions, item identities, opaque cursors and source identity. Canonical order is the native tuple (position, entry-kind rank, ID), not position alone: a turn boundary and item may share one rollout ordinal. Native ranks are turnStarted0, item1, realtime2, turnCompleted3. It does not synthesize chronology or attach/resume a worker to read history.
2. One environment/thread-scoped durable conversation store owns canonical entries, page coverage, freshness and atomic revisions. Shared catalog preview, chat, notification reconciliation and reopen use this same state.
3. Live updates amend an existing canonical item by identity. New items whose positions are not yet known remain provisional until a bounded native timeline repair supplies positions. Arrival timestamps never establish chronology. Streaming payload updates must not erase position proof.
4. Outgoing receipts and drafts remain a separate command journal. An unsent or accepted-but-not-yet-observed command is not a fabricated canonical history item; native client correlation joins it exactly once.
5. View state owns only follow-latest, an intentional reading anchor, filtering, collapsed runs and layout. It never mutates stored ordering or chooses a stale component while following latest.
6. Reconnect repairs a bounded current page and resumes source-scoped coverage. Older reads retain the original opaque native cursors. Background reads do not mark seen, start workers or replay commands.
7. Notifications identify an existing item/session and invalidate or update that same store. They are not another separately authoritative transcript. All public views must expose the committed revision and truthful freshness.
8. The web client consumes the same native-position contract. It must not independently reconstruct order from timestamps or a catalog preview.

## Qualification before replacement delivery

Use actual retained native pages privately. Verify initial current window, repeated older paging, newer output while reading old pages, reconnect, duplicate live/snapshot overlap, delayed user input, revision updates to existing items, closed-session notification update, and environment collision isolation. Check that list and chat resolve the same committed source head. Preserve meaningful stream responsiveness without publishing unpositioned messages as final chronology.

Remove the old successor-guessing and screen-order repair from native-timeline mode rather than stacking the new path on top. Explicit capability handling is required for an older runtime; do not silently claim current support or start a worker to obtain it.

## Current release boundary

149 remains a narrow containment candidate with its own native follow-mode acceptance. Its result must not be described as architecture completion. No gateway/native-daemon restart is authorized merely by this document. Native voice, pairing, drafts, accepted/unknown outgoing receipts, deliberate history reading and environment identity remain required.

# Stock Codex state and lifecycle ownership

This is the integrated architecture candidate after alpha113. It is not yet a published repair or a claim that handset performance is solved.

## Ownership

- CodexCatalog owns environment/mode identity, catalog/paging/search admission, metadata/activity/notification/attention lanes, discovery and foreground list cadence. Each lane is independently single-flight. Retiring an environment cancels its real calls; newer search supersedes an old search with a distinct publication ticket. List order still uses canonical work recency and the existing thumb/reading-safe pacing.
- CodexConversations owns stock selection and source identity, a distinct incarnation for every open, foreground/viewport lifecycle, invalidation coalescing, reconnect and head repair.
- Every selected incarnation has a SupervisorJob. Latest, older, gap, canonical position and cache-restore reads are children. Navigation/background retirement cancels those jobs and their actual OkHttp calls.
- PocketTranscript retains the established identity/order/merge/cache algorithms. It is a reducer/cache component with an injected selected-session scope, not an independent application-lifetime scheduler.
- A separately scoped storage writer preserves captured presentation checkpoints after navigation. It cannot submit commands.
- The read transport admits only explicitly listed catalog/history/status/public-update GET resources and the canonical position query. It has no command endpoint fallback.
- Durable typed/voice commands, their original identities, uncertain receipts, drafts, capture and playback retain their existing independent ownership. Closing a viewport must not cancel or replay them.
- The UI consumes one typed snapshot with separate content, read freshness, historical coverage, execution, attention count and original client deliveries. Cached content and failed reads do not become failed commands or idle work.
- Reading motion is an intent to the repository. A screen no longer holds an authoritative history request behind a composable-owned settling callback.

## Source and retirement invariants

The source key contains environment ID, backend fingerprint and thread ID, without credentials. A monotonic incarnation additionally separates A→B→A and reopened identical IDs. A completion must belong to the current incarnation and captured authenticated environment before publication. The old current/history request tickets and canonical head/version checks remain defense in depth.

Foreground stop retires presentation reads even if voice keeps its own transport alive. Reconnect invalidations coalesce; they do not resend user input. Background or hidden-viewport invalidations cannot recreate the retired owner. Foreground return verifies the newest head while retaining local history and drafts.

## Required integrated evidence

One native journey must show actual server-observed HTTP cancellation when navigating away and backgrounding, distinct A→B→A incarnations, cached reading/draft preservation, foreground recovery of a silently advanced source head, coalescing of twenty reconnect invalidations into one history read, and zero worker submissions. Existing independent-current/history, missed-event, chronology, command/receipt, keyboard/voice and environment journeys remain applicable.

## Work still required before architectural completion

The catalog migration is implemented in the candidate. The native startup/catalog journey now holds the activity lane across another catalog refresh and requires new canonical list content before releasing activity; qualify it against the old and new owners. Existing live-event reconciliation and meaningful work recency are retained. Integrated native cases and optimized APK qualification must pass before a release. Physical handset performance remains separately unverified.

This work does not alter LAX/Hollywood lifecycle, backend schemas, providers, shared worker permissions, pairing or continuation policies.

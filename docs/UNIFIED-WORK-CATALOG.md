# Unified work catalog

The work list discovers every saved, paired environment. Environment and backend
identify the source of an item; neither is the default visibility boundary.
Conversations keep their existing captured connection and command receipts.

## Implemented foundation

WorkCatalogState keys each item by environment, credential-bound owner namespace,
backend, item kind, authority and canonical ID. Each source has its own
incarnation, freshness and failure state. Removed credentials cannot be resurrected
by a delayed response. Last successful rows remain available during an outage;
their retained state is not a new assertion that work is idle.

WorkCatalogPoller admits one read per source and at most four concurrent reads.
Sources publish independently. Cancellation resets only read admission, never
cancels a work item. Observation timestamps do not affect work ordering. These
are HTTP reads; no model call or continuation command is involved.

WorkCatalogTransport is GET-only, captures the endpoint and credential, refuses
redirects, cancels the actual HTTP call with its coroutine and bounds a read to
eight seconds. Room reads additionally carry and verify the exact human binding.
A missing array or malformed identity is a failure, not an empty catalog.

## Remaining integration

The existing selected-profile catalog and conversation repositories remain in
use. UnifiedWorkCatalog now discovers all saved paired profiles independently,
with bounded foreground catalog polling and bounded metadata backoff.
UnifiedWorkList renders source-badged rooms/session families through explicit
action callbacks. Existing cards now accept source-specific presentation and
preference scopes, including an explicit restore callback. These components
compile but are not yet connected to product roots or published. Private
retained-head storage, pagination, controller integration and native journeys
remain required before publication. Navigation must validate the original paired source before
opening, including same IDs across hosts. Existing draft, question, voice,
notification and outgoing-intent namespaces must remain source-bound.

The current live room catalog provides identity and activity counts but no work
timestamp or latest-action summary. Do not sort rooms by activity.observedAt or
invent recency from polling. The backend owner must provide a viewer/audience-
filtered batched projection for meaningful cross-kind recency and previews;
per-room serial history reads are not an acceptable substitute.

The initial focused checks cover overlapping IDs/kinds, independent progress
while another read is held, single-flight admission, credential removal, retained
unavailable rows and observation time not changing order. Actual two-host native
and browser navigation remain required; these checks do not qualify delivery.


## Mac source diagnosis

A real stock-profile native catalog read and an isolated authenticated NextComp
HTTP gateway read both located the same Mac session. The latter rejected an
unauthenticated request. No work was submitted; the owned gateway and metadata
process were stopped, original terminal/runtime processes remained present and
the existing authentication file was unchanged. The first incomplete deployment
bundle and its missing shared-parser failure are retained; the corrected source
layer passed. These are connection qualification results, not a registered phone
profile or a delivered unified UI.

The Mac's separate Losangelex app-server profile is not the stock Codex profile.
Do not borrow that runtime, copy sessions into another host or call notLoaded an
idle execution state. Persistent source enrollment and safe control ownership of
existing standalone terminal sessions still need completion.


The all-source list's query and scroll anchor must belong to the list, not the
currently selected connection. Product integration must reuse the canonical
per-source catalog/conversation stores rather than permanently introducing a
second independent copy of their entities. Preserve archived/search pagination,
notification freshness and child ownership while changing the discovery boundary.

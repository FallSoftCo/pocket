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
use. The new foundation is not yet activated in either product UI. Discovery,
private retained-head storage, pagination and the unified list must be connected
before publication. Navigation must validate the original paired source before
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

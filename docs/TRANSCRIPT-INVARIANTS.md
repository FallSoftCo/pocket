# Transcript reliability contract

A displayed transcript is a projection of source-scoped send receipts, provider history and live updates. Provider item IDs are transport references; they are not always durable message identities. Codex history reconstruction can replace a live UUID with `item-1` and later `item-183` for the same input.

## Identity and delivery

- Capture environment, account pairing, thread, draft generation and send UUID before dispatch. Persist the intent first. An accepted receipt means admitted work, not completed work.
- For a user row with explicit `clientId` and `turnId`, use one semantic display identity within the existing environment/account/thread cache scope. Normalize live events, all history pages and old disk caches before merging.
- Keep the provider `itemId` and provider row aliases for ordering and pagination. Translate saved reading and provider boundary anchors when migrating caches.
- Without explicit identity evidence, retain the provider identity. Text equality never proves equivalence. Distinct send UUIDs remain distinct even when text matches.
- Transcript repair performs reads and projection updates. It must not resend a command, restart a worker, infer failed delivery from missing history, or silently retry an unknown external effect.

## Reading and freshness

- History reads use source/selection generation guards. A late response from another environment or selection must not mutate the current transcript.
- A bounded recent page must retain locally loaded history. Structural turn headers are not evidence that a missing content gap is covered. Repair proven gaps automatically and preserve the reading anchor.
- Freshness markers are event observations, not read acknowledgements or model progress guarantees. Mark presented updates only after a settled visible latest viewport.
- Following output targets an explicit end-of-content UI anchor, not the start of the last message. A single streaming row can be taller than the viewport.
- Deliberate upward reading disables following. Further output must not move the earlier reading passage. Pending questions target their actionable heading instead of the end boundary.
- Read errors preserve existing rows and expose loading/error state. Retry only safe reads under bounded backoff. Transport recovery must not activate model workers.

## Qualification

`TranscriptMessageIdentityTest` covers reconstructed IDs, six arrival orders, version precedence, identical independent sends, missing evidence, cache round trips and bounded provider aliases. `TranscriptRefreshNativeTest#providerReconstructionReconcilesLiveHistoryAndLegacyCache` checks visible counts, cache repair, live updates, navigation and retained drafts with zero reply submissions.

`HistoryReadingNativeTest#growingResponseKeepsItsNewestPassageVisible` checks tall streaming content, subsequent growth and manual reading retention. Native Important-filter and pending-question tests cover the corresponding navigation exceptions. Native fixture scenarios require independent fresh fixture state; a mixed scenario run is not qualification.

Source tests, emulator UI checks, signed builds, updater publication, handset installation and physical interaction are separate evidence levels. Passing one must not be reported as another. Current receipts are in `docs/qualification/transcript-message-identity-20261012.json` and `docs/qualification/streaming-end-anchor-20261012.json`.

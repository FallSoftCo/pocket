# Transcript reliability contract

A displayed transcript is a projection of source-scoped send receipts, provider history and live updates. Provider item IDs are transport references; they are not always durable message identities. Codex history reconstruction can replace a live UUID with `item-1` and later `item-183` for the same input.

## Identity and delivery

- Capture environment, account pairing, thread, draft generation and send UUID before dispatch. Persist the intent first. An accepted receipt means admitted work, not completed work.
- For a user row with explicit `clientId` and `turnId`, use one semantic display identity within the existing environment/account/thread cache scope. Normalize live events, all history pages and old disk caches before merging.
- Keep the provider `itemId` and provider row aliases for ordering and pagination. Resolve saved reading and gap anchors against the original cache before collapsing duplicates; bounded alias retention must not lose persisted anchors. Unknown or conflicting references are never guessed.
- Live-event novelty and position requests use the same normalized identity as display. A provider-ID change is new ordering evidence, not a new send. Deduplicate lookup attempts by message identity and provider reference; completing an old lookup cannot delete a newer queued request.
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

`TranscriptRefreshNativeTest#canonicalInputPositionSurvivesProviderChangeDuringLookup` exercises the real event ingress, an in-flight provider change, canonical ordering and repeated-event read deduplication. Its control and final evidence are in `docs/qualification/transcript-ingress-and-cache-anchors-20261012.json`.

## History-derived ordering evidence

A provider may regenerate physical item IDs while preserving the explicit client send ID. Position repair therefore sends both identities and uses a unique client identity when available. Text equality never establishes identity.

The backend records only IDs and user client IDs from complete turns already obtained by normal reads. The index is scoped to thread and turn, expires after 15 minutes, is bounded to 32 turns / 20,000 items / 4 MiB, and clears on transport disconnect. It never fetches history, starts a turn, or retains message bodies. Ambiguous or missing identities yield unavailable evidence.

A missing indexed turn may advertise `awaitingHistory`. Android may reconsider that attempt after an independently successful recent-history snapshot; this signal does not itself request a refresh. Once evidence exists, unchanged refreshes must not repeat a successful position lookup. Legacy servers without the signal keep compatibility behavior.

Message replacement must merge provider alias evidence before replacing metadata. Both live ingress and history snapshots use this rule. Metadata remains version-controlled, aliases remain bounded, and equal text is never used to reconcile sends. Saved anchors are resolved before legacy-cache alias truncation.

## Late identity and pending delivery projection

A provider row can arrive before its client send ID. Before snapshot ordering,
reconcile an exact same-turn provider reference with a unique explicit send ID.
Normalize the retained rows, the snapshot baseline and incoming rows together;
live ingress uses the same normalization. Conflicting claims remain separate.
This reconciliation is linear in retained evidence and performs no network work.

Pending local/backend delivery controls and transcript messages are separate
state, but they must not duplicate a body. An exact client-ID provider echo owns
the visible body. The delivery panel retains its status and recovery controls;
seeing an echo does not accept an unknown receipt, clear a draft or resend work.
Missing evidence, a previous pairing and independent identical-text sends remain
visible. These rules are source scoped by the existing environment/thread guards.

Reading positions and retained gap anchors must migrate along with proven identity enrichment. Restore aliases without changing offset/follow intent. An active viewport changes its key only with unambiguous evidence; existing visible keys and active gestures retain ownership. Ordinary streaming frames do not scan identity aliases.

## Independent reading-position checkpoint

Settled reading movement persists only a small source/thread-bound position
sidecar. It must not serialize or rewrite retained message bodies. Full history
checkpoints still run after completed history refreshes and on explicit lifecycle checkpoints.
An embedded position remains available for legacy, missing or damaged sidecars.

A monotonic write sequence within the cache-owner process prevents a delayed
snapshot-position write from replacing a newer gesture checkpoint. A fresh
process uses a new sequence epoch while retaining readable prior metadata. This
is a single app-cache writer contract, not a multi-process locking protocol.
Sidecars and bodies share the finished-checkpoint budget and evict together.
Only a position referencing retained content can supersede the embedded position.
Cache positions are presentation metadata, never command/read-ack authority.

## Provider metadata monotonicity

A metadata-poor live completion must not erase a previously observed client send
ID for the exact same provider item in the same thread and turn. Snapshot/live
merging follows the same rule. Explicit conflicting claims are never inferred
to be equivalent. Live events must publish the same projected identity as the
bounded cache; independent projections can otherwise disagree until refresh.

This is covered by `tests/send-identity-evidence.test.mjs`. It addresses an
observed source defect, not a verified diagnosis of every handset duplicate.

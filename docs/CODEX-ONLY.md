# Codex-only NextComp

The owner retired rooms, LAX and Hollywood from NextComp on 2026-11-07. The product now has one navigation tree and one coordinator tool contract: stock Codex sessions with native subagents. Existing multi-environment pairings, source identity, questions, queues, history, voice and immersion remain part of the product.

Android replaces a saved backend selection with Codex without moving drafts or uncertain commands. Web restores a retired room selection to the coordinator while retaining the original room namespace. Neither discovers room catalogs. The backend does not instantiate room push, progress or voice workers, and room API admission returns410. A gateway explicitly configured as native Hollywood refuses this stock release rather than silently becoming a different execution authority. Existing room services/data are separately retained.

The coordinator's dynamic-tool contract changes through its existing context-preserving migration. New and resumed coordinators have session controls only. A stale room tool invocation is rejected before side effects. Original accepted or unknown room operations are not replayed, translated or marked complete.

Fleet retirement uses the authenticated same-fleet/account descriptor and explicit retired origins. Version 169 deletes retired registrations, credentials, drafts and local command receipts, superseding version 168's retention behavior. A failed discovery is not a removal. Current Mac and phone pairings are unaffected. Removing a local record never submits or retries the original operation.

Voice admission uses the owner's 8 GiB storage reserve plus recording size and write headroom, replacing an obsolete 80 GiB default. The ten-minute capture limit and authenticated original destination remain intact.

Candidate tests, compatible APK publication, backend activation and physical-phone installation are distinct receipts. See the qualification record for actual current evidence.

Retired room-creation and Hollywood-only gateway journeys remain beside the tests as `*.retired.mjs`, excluded from the supported-product test glob. Their source is retained, but passing them would require re-enabling the deliberately removed product. The active fleet gateway test now runs stock configuration and verifies paired discovery plus explicit room API retirement. Root-owned artifact-reader provisioning has its separate `*.operator.mjs` test entry; it is not an ordinary service-user test.

## Explicit retired-data deletion (169)

The owner subsequently requested deletion, superseding 168 retention of retired environment records. Authenticated fleet retirement now removes credentials and profile metadata, environment-scoped typed drafts and outbox receipts, and retired recording files/journal rows. Browser retirement deletes only identified source-owned state; live Mac/current-host copies survive. No removed command is dispatched or retried. Non-secret retired origin and profile identifiers remain solely to clean up offline clients and prevent obsolete enrollment.

## Retired room workers in shared Codex discovery

Legacy LAX used the common Codex rollout directory while keeping paginated history
in a separate runtime database. Its records can therefore appear in stock native
metadata with empty stock timelines and writer locks owned by the legacy runtime.
NextComp excludes the explicit native `originator: lax-host-agent` identity through
its durable internal-session registry, including cached, searched and archived
catalogs. Names and workspace substrings are not ownership evidence. Ordinary
Codex sessions and subagents remain supported. Legacy histories are retained in
their original runtime store; filtering is not a claimed history migration.

## Saved-conversation membership

A native thread event is not automatically a saved conversation. Native
`ephemeral: true` runtimes have no durable resume lifecycle and stay outside the
conversation catalog, regardless of producer or title. This classification
survives reconnect and saved-cache hydration. An explicit native transition to
`ephemeral: false` releases only temporary classification; it cannot override
known product-worker ownership. Native residency/provenance survives partial
metadata updates.

`CatalogPresence` records an exact identity-bearing native metadata absence
separately from archives. It does not delete histories, drafts, outgoing receipts
or cached metadata. Timeout, transport failure, a missing list page and an
unloaded-but-readable session are not absence. Fresh native evidence restores
membership; an older negative result cannot supersede it. Unsettled outgoing
human delivery prevents silent removal. The catalog uses this evidence in its
normal, cached, search and family projections. No session is resumed or archived
to discover its status, and no model polling is added.

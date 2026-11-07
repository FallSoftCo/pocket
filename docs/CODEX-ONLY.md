# Codex-only NextComp

The owner retired rooms, LAX and Hollywood from NextComp on 2026-11-07. The product now has one navigation tree and one coordinator tool contract: stock Codex sessions with native subagents. Existing multi-environment pairings, source identity, questions, queues, history, voice and immersion remain part of the product.

Android replaces a saved backend selection with Codex without moving drafts or uncertain commands. Web restores a retired room selection to the coordinator while retaining the original room namespace. Neither discovers room catalogs. The backend does not instantiate room push, progress or voice workers, and room API admission returns410. A gateway explicitly configured as native Hollywood refuses this stock release rather than silently becoming a different execution authority. Existing room services/data are separately retained.

The coordinator's dynamic-tool contract changes through its existing context-preserving migration. New and resumed coordinators have session controls only. A stale room tool invocation is rejected before side effects. Original accepted or unknown room operations are not replayed, translated or marked complete.

Fleet retirement uses the authenticated same-fleet/account descriptor. Absent enrolled nodes leave Android's active profiles and the browser catalog; credentials, drafts and original receipts remain private and retained. A failed discovery is not a removal. Manual non-fleet Mac and phone pairings are unaffected. Explicit pairing can restore a retired profile; stale discovery cannot do so.

Voice admission uses the owner's 8 GiB storage reserve plus recording size and write headroom, replacing an obsolete 80 GiB default. The ten-minute capture limit and authenticated original destination remain intact.

Candidate tests, compatible APK publication, backend activation and physical-phone installation are distinct receipts. See the qualification record for actual current evidence.

Retired room-creation and Hollywood-only gateway journeys remain beside the tests as `*.retired.mjs`, excluded from the supported-product test glob. Their source is retained, but passing them would require re-enabling the deliberately removed product. The active fleet gateway test now runs stock configuration and verifies paired discovery plus explicit room API retirement. Root-owned artifact-reader provisioning has its separate `*.operator.mjs` test entry; it is not an ordinary service-user test.

## Explicit retired-data deletion (169)

The owner subsequently requested deletion, superseding 168 retention of retired environment records. Authenticated fleet retirement now removes credentials and profile metadata, environment-scoped typed drafts and outbox receipts, and retired recording files/journal rows. Browser retirement deletes only identified source-owned state; live Mac/current-host copies survive. No removed command is dispatched or retried. Non-secret retired origin and profile identifiers remain solely to clean up offline clients and prevent obsolete enrollment.

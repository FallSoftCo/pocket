# Queued messages

A queued reply is a saved future input, separate from accepted transcript messages. Android and web display pending requests in the conversation with queue state and edit/remove controls. Held requests remain visible as paused. Sending and unknown delivery retain their existing protections.

The backend publishes one complete versioned outgoing snapshot in the existing reply event and existing admission, receipt, mutation and history responses. A per-thread SQLite revision advances transactionally with outgoing changes, including deletion. Reading revision and rows uses one savepoint. No additional endpoint, model polling or notification stream is introduced.

Clients retain this metadata under environment, backend credential identity and thread. An older history response cannot overwrite a newer outgoing snapshot or resurrect a removed request. Queue metadata does not alter canonical transcript ordering. Initial admission and sending transitions now publish their state immediately to other connected devices.

Queue dispatch first checks FIFO ownership, then reads current native thread metadata without history pages. Only current idle status permits next-turn admission; stale historical in-progress records cannot keep an idle queue stuck. Existing native busy rejection, stop/held behavior and uncertain-delivery rules remain authoritative.

Qualification uses the actual gateway, HTTP pairing, WebSocket, SQLite and process restart with a deterministic stdio provider. It checks cross-device admission/edit/removal, late history, durable revision, FIFO and no dispatch of a cancelled row. This is transport and application proof, not provider inference, physical-phone or private terminal-buffer support. Native UI and exact optimized APK evidence belong to the versioned private release receipt.

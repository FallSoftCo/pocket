# Named development environments

Android retains multiple named remote pairings alongside Phone. Open the footer Settings control, then the development-environment selector; Add environment pairs an additional HTTPS backend. Existing workstation and phone pairings are discovered without replacing their tokens or rewriting saved drafts. Re-pairing the same normalized origin retains its profile identity.

Each profile owns its endpoint, token, coordinator, session history, drafts, settings, read markers, notification actions, outgoing receipts and update source. Thread identifiers alone do not identify work across environments. Switching clears selected presentation state and restores the destination profile; it does not resume an unknown delivery. Requests and deferred notification actions capture their originating profile and credentials before dispatch. Late responses from another profile cannot replace the selected conversation.

Recording blocks environment switching until capture finishes. The independent phone notification monitor remains active while a remote environment is selected. Remote Firebase registrations are profile-scoped; inactive-environment alerts remain visual and do not generate speech through the selected backend. A backend using a different Firebase project needs compatible push configuration; live authenticated updates remain available.

This release changes Android, not backend discovery or stock Codex authentication. Each remote must already run an authenticated, reachable NextComp backend with its own one-use pairing code. Pairing does not migrate work or copy device databases. Browser parity remains a separately owned change.

## Evidence boundaries

Unit coverage checks legacy storage preservation, profile identity, frozen destinations, ambiguous/failed delivery exclusion and strict phone-loopback identity. Disposable native tests exercise same-ID histories on separate origins, preserved drafts, source-bound background replies, unknown-delivery holds and independent phone notifications. A separate native test adds a real HTTPS backend, checks authenticated status, preserves other profiles and revokes only its disposable pairing. These checks do not certify every provider, physical handset interaction or push-network condition. See the versioned qualification record for exact release and installation evidence.

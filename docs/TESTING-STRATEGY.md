# Testing by user outcomes

Test totals are not a quality metric. Passing units do not establish that the installed app works. Apply this policy to future tests and release reviews; preserve existing regression gates until replacement coverage demonstrates equivalence.

## Evidence categories

Unit tests establish isolated invariants. Integration tests exercise named real collaborating components. Native component tests establish Android layout/callback behavior. Critical journeys traverse actual app navigation, transport and persisted state. Deployment checks verify the exact optimized signed artifact. Visual and performance review establish motion, reading flow and measured latency separately. Always name substituted boundaries and unverified claims.

## Critical journeys

| Journey | Outcome to verify |
| --- | --- |
| Pair, launch, update | Visible loading, reachable keyboard actions, cancellation cannot hijack navigation; optimized artifact update smoke |
| Switch and reply | Correct target/draft, chronological messages, single bubble through echo/history/restart |
| Read live/older history | Fresh content first, automatic pagination, stable reading anchor, usable growing response |
| Voice | Immediate capture state, stop sends to original target, typing preserved; physical keys/audio need phone evidence |
| Reconnect/recover | Pairing/drafts survive, uncertain delivery reconciled without replay |
| Immersion | Single readable flow, stable surroundings, selection/links usable, full motion reviewed |
| Environment switch | Named profiles retain state; reads and actions cannot cross backend identities |

Exercise app + HTTP/WS + real storage with deterministic provider substitutes. Such tests cannot qualify upstream capacity, billing, FCM, real Tailscale or physical handset behavior. No live user tasks or external writes as fixtures.

## Writing and running tests

Before adding a test, state the user risk, failing boundary and why existing coverage cannot detect it. Prefer extending a coherent journey or parameterizing an invariant over another near-identical case. Assert observable outcomes rather than implementation choreography. Remove regressions only after equivalent replacement coverage is demonstrated.

Run affected focused checks while editing. Run broad release checks once the candidate stabilizes. Repeat only after changes, failures or unresolved concerns. Do not add tests solely to increase totals.

## Setup and triage

Preflight artifact identity, emulator ownership, permission state, pairing, foreground package and fixture readiness. Record these in receipts. Separate setup failures from product failures; retain hierarchy, screenshot and transport evidence. A permission dialog obscuring the app invalidates that observation, not the renderer. Preserve the original failed receipt and correction. Synchronize to observable rendered state instead of arbitrary sleeps. Model readiness is insufficient: wait for the actual card/content. Use one UI framework per journey and its own clock synchronization. When pagination deliberately coalesces demand, await cursor advancement at the loaded edge before issuing the next gesture.

## Release acceptance

Report journey outcomes, defects, artifact identity, visual review and remaining limits. Missing phone access means phone delivery is unverified. Emulator video rate is not physical frame pacing. Position/ink assertions cannot establish comfortable reading.

The existing signature/source/native receipt gate remains intact as provenance evidence. Consolidate its individual case runs only with deterministic grouped setup, per-case results and equivalent coverage. Do not simply delete cases to shorten a green build.

## Current audit, 2026-10-12

The inspected suite has 169 files across backend, Android unit and native directories. This is inventory, not quality. The current release policy requires 30 native cases, many component checks rather than complete journeys. Signed r04 passed automation but motion review found layout defects and publication remains held. A permission dialog also invalidated earlier native failures. These are concrete reasons to improve journey coverage, setup and visual acceptance rather than accumulate more units.

A real app-to-backend send/echo/restart journey now passes with provider RPC substituted; see [its boundary-specific evidence](qualification/android-real-backend-20261012.json). Grouped runner consolidation and broader app-to-backend journey coverage remain unfinished. No existing tests or safeguards have been removed.

## Primary guidance

Android recommends critical user journeys close to production and distinguishes unit, component and end-to-end evidence:
https://developer.android.com/training/testing/fundamentals/strategies
https://developer.android.com/training/testing/fundamentals/what-to-test
https://developer.android.com/training/testing/ui-tests

The real-backend journey now covers thumb pagination and retained passage geometry across cold restart; see [history evidence](qualification/android-real-history-20261012.json). This is retention/navigation evidence, not a smoothness or human-reading assessment.

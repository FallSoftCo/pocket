# Rooms and sessions: shared UX completion

User requirements reconciled 2026-10-16. This is an open acceptance contract, not a release qualification receipt.

The existing room implementation shares some primitives but still has a separate screen structure, address header, footer, list cards and alphabetical ordering. Those are confirmed gaps, not required differences between backends. Previous release evidence for message correctness or voice controls does not establish UX parity.

## Required implementation

- Use a shared conversation scaffold, composer, bottom toolbar, navigation, loading/error surfaces and properly anchored menus. Remove the room-only top address header. Put room, recipient, audience and reply targeting in the same bottom interaction system.
- Keep room-specific multi-agent, public/private, concurrent-delivery and permission semantics explicit. Do not erase them or fake session-only actions to achieve visual similarity.
- Unify the work list experience: dense fixed-height cards, meaningful latest material content, clear current work/attention/idle/unavailable states, discoverable useful work, and direct entry to interaction.
- Room lists update live like sessions. Rank by actual relevant work activity, never inventory refresh/observedAt. Deliver genuine activity promptly while keeping active peers stable and preserving reading/touch position. Reconnect must catch up membership, content and ordering without reopen or duplicate actions.
- Environment/workstation selection stays near its initiating control. Avoid detached/nested floating menus, excessive eye travel, extra permanent rows and toolbar width wasted by inconsistent layouts.
- Preserve keyboard-aware Back, Enter-to-send, source-bound mic/physical volume recording, captured recipients, separate drafts, scroll/read position, original outgoing IDs, accepted/unknown receipts, questions/approvals and useful notifications through navigation, reconnect and process recreation.
- Retain stock sessions, native rooms, phone-local execution and existing named environment pairings. Scope all state/actions to the correct environment; no silent migration or delivery replay.

## Verification and completion

Audit shipped behavior against this contract. Exercise shared native journeys on sessions and rooms with keyboard open/closed, voice start/stop, room/recipient changes, attention, cancellation, history pagination, new live messages, reconnect and process recreation. Include normal and larger text and actual optimized release payloads. Use screenshots to inspect layout, not as substitutes for interaction evidence.

Measure typing and scrolling under active incoming updates. Emulator timing does not prove handset responsiveness. Report physical-phone installation, physical controls, actual provider audio and rendered push separately; no unit-test-count or synthetic no-provider completion claim.

Catalog meaningful preview/work-recency projection requires the canonical backend contract owned by the source coordinator. Progress independent client work without fabricating fields or scanning every room history. Record the exact dependency when needed.

Finish means integrated, qualified, compatible delivery with explicit remaining limits, not a handoff or patch-specific pass. Maintain checkpoints and resume this authorized work after interruptions by reconciling files/artifacts/external effects. Do not create recurring model polling, blanket agent wakeups, or resume unrelated stopped work. User has provided sufficient guidance; request only a genuinely necessary unresolved decision or unavoidable OS action.

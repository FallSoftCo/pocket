# UX evidence and parity work

The November 9 review starts from Android 180 and the same backend/web release.
The owner reports installing that build; a read-only package-manager check also
reports version 180. That proves installation, not visual or performance quality.

## What is measured today

Android's explicit **Report a slowdown** action records at most 15 foreground
seconds of frame durations, input/layout/draw breakdowns, main-queue delay and
bounded stack samples. It exports locally and stops when backgrounded. Voice has
separate capture, transport, response and audio timing records. Neither provides
a general session-selection-to-visible-message distribution. Web does not yet
have equivalent local UX aggregates.

Native journeys and optimized-artifact screenshots are retained release evidence.
They are not continuous monitoring. The review found that screenshots taken by
the large-font/IME footer test were left inside the test app rather than bound to
the release receipt. Its geometry assertions therefore could not substitute for
review of those actual images. The card clip also used a different corner radius
from its border; visible missing corner rims need a production fix.

## Current assignments

| Workstream | Concrete result required |
| --- | --- |
| Local UX measurement | Bounded recent foreground aggregates for session selection, initial content, fresh content and submitted-action feedback; preserve cancellation and superseded-navigation outcomes. Export without conversation text, account/session IDs, URLs, credentials or keystrokes. |
| Visual quality | One card shape for fill/border/clip; retain actual normal/large-font and IME-on/off images with current-run identity and hashes. Review narrow/wide screens, safe areas and text/controls, not just node existence. |
| Native parity | Compare installed native protocol with the UI; implement supported MCP OAuth initiation and correctly correlated completion. Keep unsupported worktree/hook mutation unavailable rather than simulating native support. |

All three agents retain medium reasoning effort. The root owns integration,
capacity, artifact identity and release. A completed implementation handoff is
not a deployed feature or a passed visual check.

## Decisions supported by the measurements

- Separate first cached content from fresh accepted content. Fast stale content
  must not hide a long wait for the current conversation.
- Separate transport completion, state application and laid-out content.
  Layout callbacks and browser frame callbacks do not prove optical latency.
- Keep interrupted, abandoned and superseded operations in outcome counts;
  exclude them from successful-duration distributions without erasing them.
- Compare like-for-like builds and journeys, recording sample counts and capped
  retention. A percentile over a handful of samples is not a population estimate.
- Use a measured slow path to choose the component to inspect; do not infer that
  animation, the model, network or backend is responsible from total duration.
- Pair border/clipping geometry checks with retained-image review. A screenshot
  filename, passing click or accessibility bounds alone cannot prove intact ink.

The default is local bounded evidence, not an analytics service, model polling,
automatic uploads or new background workers. Expensive frame/stack capture stays
explicit. Measurements must not create per-frame Compose state changes or impair
typing. Physical phone evidence remains distinct from software-emulator evidence.

## Release boundary

For the next candidate, extend existing composed journeys at the actual failing
boundaries. Preserve the original failures and complete the affected native and
optimized release checks once the integrated source is stable. The existing
180 delivery remains available throughout. No new release number or successful
delivery is claimed by this plan.

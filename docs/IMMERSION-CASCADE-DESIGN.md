# Continuous reading-order immersion waves

User correction, 2026-10-12: equal local fade/sweep speed, staggered top-to-bottom starts, endlessly repeating waves, each language visible for a few seconds. Do not accelerate later lines to share a completion deadline.

## Confirmed current defect
immersionWipeLineProgress multiplies normalized paragraph progress by (lineCount - 1 + 1.6), then divides by 1.6. With a fixed 4200 ms animation, a line's sweep lasts 4200 * 1.6 / (lineCount - 1 + 1.6): 4200 ms for one line, about1461 ms for four, and about634 ms for ten. Adding content therefore changes local speed. The shared control cycle separately holds target18 seconds/support6 seconds. These are implementation choices, not perceptual thresholds.

## Replacement architecture
Use one monotonic visible-screen epoch and actual native line geometry. Separate local transition duration from onset delay and repetition period. A line's local clock is elapsed minus its reading-order onset; never divide a fixed whole-paragraph deadline among its lines. Each line has the same local transition profile. Later lines begin later and finish later. Paragraphs and controls must use the same screen-coordinate wave, rather than independent paragraph timelines that restart at each card.

Prototype: a complete local sweep of1200 ms, a line-onset separation of350 ms at normal text size, and a stable local language hold of3000 ms after each sweep. Repeat the two language phases continuously with an8400 ms local period. These values are starting points for native visual comparisons, not established reading thresholds. Derive vertical delay from physical/native text geometry and a fixed wave velocity; preserve the same clock within a viewport. Account for text scale and line spacing explicitly. Do not make a paragraph's line count affect the speed. Mirror within-line direction for RTL, but retain top-to-bottom progression.

Use complementary incoming/outgoing ink masks, keeping total coverage at unchanged glyphs; never fade the entire paragraph through black. Preserve target-grammar lexical slots, native line breaks, normal-size text and original canonical copy/send content. The current reserved-slot spacing issue remains separate; timing must not disguise it.

Freeze motion during touch/scroll, text selection, reduced-motion preference, screen-reader exploration and background lifecycle. Resume from a stable visible form without replaying missed waves or catching up at accelerated speed. Geometry changes must not restart every label independently or create instantaneous language jumps. Offscreen content must not own timers or keep a frame loop alive.

## Qualification before delivery
Compare one-, four-, and ten-line content at the same measured local speed; compare separate controls and body paragraphs in one viewport. Capture full native sequences and count onset/completion frames at the captured sample rate; distinguish capture rate from app frame pacing. Verify several complete cycles, text scale, scrolling, navigation, taps, copy/selection, keyboard, lifecycle and reduced motion. Check actual visible appearance rather than relying on unit test counts. Preserve previous failed release receipts. This document is a design and diagnosis, not a shipped implementation.

## Primary evidence and limits
Slattery and Vasilev2019, An eye-movement exploration into return-sweep targeting during reading:
https://pmc.ncbi.nlm.nih.gov/articles/PMC6647890/
The experiment studied32 English readers and multiline stories. Reading alternates fixations and saccades, with return sweeps from one line toward the next. Bolding line-initial words reduced targeting errors without improving measured reading time. This supports respecting line progression and avoiding claims that stronger animation necessarily improves reading. It does not validate bilingual cycling, these proposed timings, or continuously moving text. Eye-tracking behavior cannot substitute for native app visual qualification or user feedback.

## Implementation checkpoint
ImmersionReadingWave.kt implements the independent local clock and complementary coverage policy. Compilation and boundary checks passed on OZZZ-OS. It deliberately has no production call sites yet: the existing body phrase attention queue and controls clock must be replaced together. Native visible semantics, selection and hit-testing must follow the actual locally shown language rather than a paragraph-wide switch. This checkpoint does not fix the installed app. See qualification/immersion-reading-wave-policy-20261012.json.

## Native runtime checkpoint
The production MainActivity now owns a single display-clock host. Controls and body use the same viewport-coordinate frontier, fixed paired grammar forms and complementary coverage. Native semantics change at each lexical slot rather than one paragraph-wide handoff. The clock yields to normal interaction/lifecycle, selection-owner holds and reduced motion; offscreen enrollment uses native visibility. Empty projection boundary slices were creating phantom phrase reservations and rejecting body alignment; these are now omitted. Controlled-clock and natural Android-display-clock journeys passed, plus existing clipboard/action/geometry regressions. Source and exact evidence limits are in qualification/immersion-reading-wave-runtime-20261012.json. This is still a candidate: no signed frozen release qualification or phone delivery. Scrolling/reanchoring, actual live selection-menu integration, IME, blackout and long paragraphs remain qualification work before release.

## Interaction follow-up
Actual native long-press revealed that Compose’s modern context-menu lane bypasses the legacy TextToolbar wrapper. A focus-only experiment correctly froze/copies text but retained focus after touchscreen button action; it was rejected and removed. The supported menu-lifecycle fix and scroll/reanchoring checks remain release prerequisites. Native fixture teardown now explicitly stops its own frame clock before ActivityScenario idle cleanup, preserving failure receipts without hanging. The blackout clock guard is candidate code, not yet native-qualified.

## Native menu lifecycle repair
Activity.onActionModeStarted/onActionModeFinished now track platform-owned menus by identity, scoped to the activity, and pause the shared clock. This public lifecycle covers the observed modern Compose toolbar without replacing native controls. The actual long-press/select-all/copy/return stages passed; the combined blackout journey is still failing because a restored native menu remains active after the tested interaction. The release gate remains held; preserving selection and input focus is not replaced by forced focus clearing. Rejected focus-only and native navigation experiments remain in the qualification receipt.

## Real interactive gesture qualification
The original natural fixture disabled phrase help, so it could not prove help-tap interference. It now enables conversation phrase help and captures native failures. Its previous continuous-frame idle wait was removed from blackout entry. A noninteractive run required an operator tap to unblock idle and remains assisted evidence only. The real interactive baseline loses Copy after Select all; the full journey remains failed. A bounded pointer tap timeout now keeps long holds from dispatching help, with five existing native action/clipboard/geometry regressions passing. Speculative focus clearing was removed. The timeout repair does not claim to resolve the remaining selection failure.

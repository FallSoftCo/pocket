# Shared inline reading geometry — implementation in progress

The existing paragraph renderer measures each language independently. Reserving
maximum paragraph height does not reserve phrase positions: unchanged words can
cross a line boundary during the cycle. The preserved native regression proves
this on a 240dp paragraph. This defect remains open.

The new structural planner uses explicit immersion-reserve phrase identities,
not fuzzy text matching. It extracts unchanged and replacement runs from all
forms, preserves each form's annotated substring, and rejects inconsistent IDs,
overlapping marks and changes outside replacements. This is a prerequisite,
not a renderer correction or a shipped UX claim.

The renderer must place all forms using one line-break plan. Each replacement
fragment reserves the maximum measured advance of its alternatives. Unchanged
runs keep the same origin throughout the transition. A phrase exceeding a line
must split at valid word/shaping boundaries; it must not become a giant atomic
placeholder. Complex-script shaping, bidirectional ordering, font scaling,
rich styles and maxLines/ellipsis must remain native-text aware.

Drawing, native hit testing, accessibility and selection must all use that same
plan. A visual-only overlay with the old paragraph's tap positions is invalid.
Links and inline reveal actions retain their original callbacks and phrase IDs.
Only one active lexical form is exposed to accessibility; transition layers
must not duplicate readout or append parallel translated lines.

Before enabling the renderer:
- Reproduce the preserved failing native word-position case with explicit marks.
- Verify longer, shorter and multiline replacements at normal and 2x font size.
- Verify surrounding words and links keep position before, during and after wipe.
- Verify the visible phrase's native link/reveal tap target and screen-reader text.
- Inspect actual frames for reading-direction cascading wipe and ink continuity.
- Verify long text performance and reduced motion; measure, do not infer.

This work changes no production update channel. Alpha86 remains the qualified
transcript release. The draft native regression is intentionally not presented
as passing.

Candidate progress: SharedInlineImmersionText now uses native inline placeholders
for shared fragment geometry. Three native checks pass, including 34sp text and
clickable replacement links. Six structural/chunking tests pass. It remains
disabled: global line cascade, accessibility/selection, oversize unbreakable
words, production cue tracking and frame/physical review are not qualified.

Overflow correction: oversized unbroken fragments now reserve full native
wrapped height in both forms. All four native cases pass after this change.
Global cascade and accessibility/selection are still release prerequisites;
there is no production integration or new published APK from this candidate.

Accessibility correction: Android exported the replacement twice (standalone
word and full paragraph). Inline child semantics are now cleared while native
touch handling remains; replacement links are available as parent custom
accessibility actions. Five final native cases pass, including exported sentence
uniqueness, real injected link taps and original callback via custom action.
TalkBack utterances/gestures and selection remain unqualified; the candidate
is still disabled and alpha86 has not changed.

Shared wipe implemented: actual native placeholder rectangles define one
reading frontier for the whole paragraph. Same-line slots share horizontal
progress; later physical line bands wait. RTL mirrors the whole frontier.
Native measured line height avoids fractional-sp rounding creating phantom
bands. Four geometry tests and six final native cases pass; one native
intermediate image proves unchanged common ink and changed replacement ink.
This is a single-phase pixel check, not a watched frame sequence or phone
performance proof. Cross-cue reservations, production cue visibility,
lexical/action handoff consistency and physical review remain open.

Cross-cycle reservation implemented: all supported annotated forms contribute
to shared fragment width/height before any particular cue begins. Current
from/to still determine the lexical transition. Seven final native cases pass.
The new case changes two phrases in succession and verifies the surrounding
passage stays fixed; removing full reservations makes that same fixture
reflow, proving the check detects the intended regression. Production
ReservedImmersionText is not yet wired to this candidate.

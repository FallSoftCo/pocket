# Inline immersion: reading-flow review

## Evidence reviewed

The actual 44.26-second qualification conversation capture shows punctuation
beside the phrase after the repair, but substantial unused reserved width before
the unchanged following clause. Five-second samples also contain intermediary
mixed glyphs; these samples cannot establish temporal comfort or comprehension.
The optimized r04 recording remains a retained failed visual candidate.

Slattery and Rayner (2013) report two eye-movement experiments: interword spacing
affects word segmentation and saccade target selection, with interactions between
letter and word spacing. Reviewed evidence here is the paper abstract, not an
independent reproduction or full-method review:
https://pubmed.ncbi.nlm.nih.gov/23709061/

Rayner's reading review covers perceptual span, information integration across
saccades and individual differences. It does not validate this interface or give
a universal number of frames required to understand a bilingual transformation:
https://pubmed.ncbi.nlm.nih.gov/9849112/

W3C's pause/stop/hide guidance supports user control over automatically changing
content; applying it here is an accessibility design inference, not a claim of
complete Android or WCAG compliance:
https://www.w3.org/WAI/WCAG22/Understanding/pause-stop-hide

## Consequences for this implementation

- Do not stretch letter spacing or artificially justify short translated chunks
  to disguise the unused reservation; preserve native word shaping and spacing.
- Do not choose timings from an invented human frame threshold. Distinguish
  measured artifact frames, device pacing, fixation research and user comprehension.
- Retain the user's single inline flow, reading-direction wipe, longer-form
  reservation, direct in-place help and motion control requirements.
- Per-fragment maximum-width reservation is the confirmed source of blank gaps.
  A replacement must compare reservation at coherent phrase/line boundaries and
  preserve external paragraph/reading anchors. Merely shrinking a slot each cycle
  would create the reflow the reservation was intended to prevent.
- Compare complete source, target and intermediate states with ordinary and large
  fonts, unequal phrase lengths, punctuation, links and selection. Judge actual
  reading flow and adjacent text movement separately from component pass counts.
- Current animation geometry and native position tests establish mechanics only.
  No current evidence proves this interaction is world-class or human-validated.

## Next design experiment

Prototype coherent phrase reservation before replacing the existing renderer.
Measure natural wrapped bounds for both forms using the remaining first-line
width and subsequent full-line width, rather than independently packing text
chunks and reserving each ordinal chunk's maximum. Keep following content's
outer anchor fixed and inspect whether internal reservation moves unused space to
a natural phrase/line boundary. Reject the experiment if it clips, splits links,
duplicates selection content or causes common content to jump.

No revised renderer or timing has been shipped from this review. Publication
remains held. A design hypothesis is not qualification evidence.

# Immersion renderer contract

Inline language replacement retains one content flow and native Text semantics,
links and visibility reporting. No parallel translated line or help modal is
introduced. Current timing constants remain prototype tuning, not claims about
human perception thresholds.

Complementary text masks combine additively inside a transparent isolated group,
then that group composites normally onto the screen. Ordinary source-over between
the two masks multiplies their coverage: aligned unchanged ink drops to 75% at
half coverage. Adding directly to the screen would brighten colored text over a
non-black background. The isolated group avoids both errors. Endpoints keep the
ordinary native Text draw path; only an active cue uses the additional layer.

`ImmersionInkNativeTest` samples an actual native glyph where the feather crosses
it. It covers white-on-black and colored-on-gray ink, including RGB preservation.
This is raster evidence, not physical-phone frame pacing or a perception study.
One additional isolated layer is used during a cue; its physical rendering cost
still requires measurement.

## Unresolved layout and motion qualification

Paragraph variants still wrap independently inside their reserved overall box.
A longer phrase can move unchanged surrounding words to another line. Native
geometry reproduced that failure; it remains tracked in
`qualification/immersion-layout-open-20261012.json`. A paragraph bounding box does
not prove stable phrase placement or natural spacing. Do not mark immersion
complete based on compositing, unit tests or this pixel regression.

The remaining renderer work must qualify phrase placement, normal-size reading,
rich links, inline help, reduced motion, visibility-driven scheduling and actual
motion/frame review without introducing blank gaps or duplicated language flows.

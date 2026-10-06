# NextComp object language

The approved v03 look establishes small, tangible tools with clear silhouettes.
The refined revision treats each object as something constructed: curved shells,
recesses, functional joints, wire, folded surfaces and distinct working parts.
A symbol earns extra detail when that detail explains its function or volume.

## Form and recognition

Keep the familiar meaning in the primary silhouette. A microphone has a capsule,
yoke, stem and base; the wrench has an open jaw; the keyboard has raised keys;
the bell has a hollow skirt and separate clapper. Navigation symbols use round
structural rods. Stop and Pause use substantial ceramic keys. Play has a varying
biconvex face, and Send has folded wings and a raised keel.

Use broad bevels and rounded transitions to catch light at small sizes. Keep
functional recesses dark, and maintain air between distinct elements. The
keyboard's keybed and microphone's wraparound vents should explain their assembly
at larger sizes without overwhelming the silhouette at 16–24 physical pixels.
The brain uses two broad cerebral forms with folds, rather than separate beads.

The intended UI profile is front to about ±30° yaw with moderate pitch.
Side and rear studies document limits. An arrow or ellipsis cannot preserve the
same projected meaning when viewed exactly along its axis. Full rotations remain
reserved for the established busy indicators; controls make restrained swivels.
Fixed crops preserve their pivots and occupied space throughout playback.

## Materials and light

Porcelain/enamel supplies the principal readable form; teal identifies working
surfaces; graphite supplies recesses; silver identifies mechanical structure.
Coral retains the warning meaning. Gold is a sparse construction accent on the
pencil, bell and microphone pivots. These are material roles, not arbitrary tints.

The default rig uses a broad upper-left key and a cool fill. It reveals thickness
and curvature without depending on shadows outside the transparent asset.
Native studies include charcoal enamel on cream/daylight, warm studio and midnight variants. Each
changes authored material values and light together. Daylight is reviewed on a
light surface; warm and midnight variants use their corresponding dark surfaces.
A theme should retain semantic colors, recess separation and broad highlights.
Do not recolor these assets using a flat UI tint, which destroys their modeled
lighting. The current theme studies are review assets; adding a theme selector is
separate from the default resource integration.

## Motion and production

Use 60 actual rendered samples per second with the existing loop durations:
120 frames for a two-second swivel, 160 for a 2⅔-second full rotation. On a 120Hz
display, each native frame can occupy two refreshes. Display timestamps determine
the frame; overdue frames are skipped rather than played in a catch-up burst.
This is a 60fps asset stream, not a claim of 120 unique rendered frames per second.

Small lossless WebP grid pages keep texture dimensions portable. Controls use
128px cells; the two small busy indicators use 96px; the N loading mark retains
256px cells and its existing cubic construction, with a 128px atlas selected for smaller density-scaled uses such as the footer. Current and next pages are held
by visible players; a separate decoded LRU retains at most 32MiB. That cache
limit is not a limit on all live bitmap/GPU memory. Measure resident memory and
frame delivery on the target handset before claiming handset smoothness.

Compose draws frame changes in its draw phase, without per-frame decoding,
composition or layout. Decoding/prefetch runs asynchronously. Freshly decoded pages call `Bitmap.prepareToDraw()` before publication to request early texture upload; cached/live pages are never mutated. Missing pages fall
back to the static symbol. Motion pauses outside the resumed activity and when
Android's animator duration scale is zero, including a live setting change.

Author scenes as native Houdini Indie files. Model and package on OZZZ-OS and
render on OZZZ-GPU-RENDER through guarded farm plans, with verified source hashes,
RGBA masters and device telemetry. Retain native 3840-square masters and
1920-square deliveries. Review real-size sheets, perspectives, lights, motion and
native UI independently; a successful render is not a visual approval.

Implementation references: [Android bitmap upload guidance](https://developer.android.com/topic/performance/vitals/render), [Bitmap.prepareToDraw](https://developer.android.com/reference/android/graphics/Bitmap#prepareToDraw()), and [Compose display frame clock](https://developer.android.com/reference/kotlin/androidx/compose/runtime/MonotonicFrameClock). These describe mechanisms; they do not establish our handset frame delivery.

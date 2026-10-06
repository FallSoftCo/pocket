# NextComp assembled 3D symbols

This workstream replaces the 37 constant-depth silhouette symbols (35 controls,
Codex and User). The established cubic N launcher/loading mark is separate.
`SymbolIcon` keeps its public names, semantics, sizes, button behavior and press
response. The refined work targets actual 60fps playback using paged atlases and
the display clock, with foreground policy and reduced-motion fallback. The N
keeps its established cubic geometry and gains the same motion pipeline.

`source/build_objects.py` authors original geometry in Houdini Indie. It never
imports vector paths or fills/extrudes a silhouette mask. Objects use assembled
volumes and meaningful construction: a microphone capsule in a cradle on a
weighted stand; a keyboard shell with raised keys; a hollow revolved bell with a
clapper; bent round paperclip wire; an open wrench head; a convex lens and handle;
a folded plane with raised keel; a monitor shell, recessed screen and stand; and
a biconvex triangular play key. The abstract controls remain recognizable symbols:
rounded rods form arrows/checks, spherical buttons form ellipses, and ceramic
volumes form stop/pause keys. These are stylized physical objects, not photoreal
product replicas. Part names, material assignments and XYZ extents are recorded
in the native geometry receipt.

The Codex circle-terminal remains derived from the official OpenAI mark, retaining
[its existing source and trademark provenance](../voxel/source/references/PROVENANCE.json).
Its new native construction uses a torus and round prompt/cursor rods. It is not
presented as an original NextComp brand mark. All other designs and builders are
original to this brief. Dependencies are Houdini/Karma, OpenImageIO, NumPy,
Pillow, FFmpeg and Zstandard; no third-party tutorial scene or stock model is used.

Preparation and packaging run on OZZZ-OS; render jobs use Karma XPU on
OZZZ-GPU-RENDER with CPU fallback disabled. Farm plans and runtime receipts bind
actual Indie scenes, geometry, file checksums, RGBA dimensions and measured GPU
activity. Square native masters retain the established 3840 × 3840 atlas aspect;
1920 × 1920 derivatives and transparent app PNGs are separate deliveries.
Temporary task volumes keep the farm's 35 GiB free-space guard intact.

The look uses porcelain, teal, silver and graphite, with coral warning rims and
gold pencil/bell details. Broad forms take priority over microdetail. The CGI
library's source-grounded modeling packet and Pixar *Character Design: Visual
Complexity in Brave* informed the review principle: preserve broad shape while
adding detail. This is an original adaptation, not a reproduction of a studio
asset or a claim that the studio used our icon construction.

Review artifacts and inventory live under `qualification/`; the final qualification
record lives at `docs/qualification/nextcomp-modeled-symbols.json`. Real-size
sheets cover 16, 20, 24, 32 and 44 physical pixels, conservatively below many
Android density-scaled uses. Synthetic raster context boards are explicitly
labeled; native emulator screenshots must provide separate UI evidence. Review and
master downloads are published in the main 8bar gallery.

Rerun builders and packaging only through the recorded guarded farm plans.
Use a new scene/render directory for changed geometry or settings. Preserve
existing render identities and completed frames. Static crops are shared across
all 120 swivel samples; 160-frame full rotations are limited to the established
Codex/Psychology busy indicators. No 3D scene or per-frame mesh work runs on Android.

The approved v03 assets remain the baseline review. The refined v06 geometry adds
a continuous microphone housing with wraparound vents and pivots, an inset
keyboard keybed, a genuinely hexagonal pencil and sharpened tip, broad brain
hemispheres with surface-following folds, and a connected bust neck. Its native
perspective and material/lighting studies cover the intended front/quarter views,
side/rear limits, daylight, warm studio and midnight. See
[the design language](DESIGN-LANGUAGE.md) for form, material, lighting and motion
rules. Render receipts, resource validation and native performance measurements
are separate evidence; 60fps assets do not alone prove 60fps handset delivery.

The refined delivery combines v06 geometry and 120-frame swivels with v07 crisp
160-frame full turns and four separate view/theme rigs. v07 uses zero camera
shutter **and disables the Karma motion cache**, avoiding zero-step cache errors
and blurred angle jumps. Studies are a checksummed collection of four independent
native sources, explicitly not a single native nine-frame sequence. Daylight
uses charcoal enamel on cream to preserve contrast; warm and midnight use
authored material/light variants. These studies do not add a live theme selector.

Native masters are delivered as a `.tar.zst` download. The archive is tested
and its decompressed TAR hash must equal the original before the redundant
uncompressed archive is removed. Scene/geometry and all completed native frames
remain on the farm. `tar --zstd -xf native-modeled-masters.tar.zst` extracts it.

The master download contains every native3840 RGBA HDR frame, all1920 RGBA
HD PNG frames, a first3840 PNG per cohort, and editable Indie scenes/geometry.
Entire tonemapped3840 PNG sequences also remain on the farm; they are omitted
from the download to avoid duplicating the native UHD HDR masters.

Public qualification receipts preserve measured results while replacing private workstation roots with `<private-workspace>` and using relative gallery links. Original unsanitized receipts are retained privately by the integration owner. Published PNG metadata is stripped where present; every decoded PNG pixel was verified identical to the original handoff. Source validators resolve their qualification inputs relative to the checkout.

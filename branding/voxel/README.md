# NextComp native voxel symbols

Original NextComp symbol geometry and the colored, extruded N are authored in
Houdini Indie 22.0.429. The N has ivory faces with green, yellow, coral and cerise
sidewalls; it is the letter itself without a cube pedestal. Raking light,
beveled edges and transparent RGBA output preserve depth at mobile sizes.

`source/build_symbols.py` constructs 35 original interface symbols and the N.
`source/build_cubic_mark.py` is the current N builder: 1,600 equal-edge cubes
in eight depth layers, with a genuine 360-degree Y rotation. All axes measure
0.04018 per voxel and object scale remains [1,1,1]. Shape and depth come from
grid occupancy. Native measurement receipts are retained under `review/v05/`.
`source/build_spinning_mark.py` retains the earlier prismatic iteration.
`source/build_roles.py` adds an original person silhouette and a voxel extrusion
of the official Codex circle-terminal mark. The latter is an OpenAI trademark,
not an original NextComp mark: exact source SVG and publisher/package provenance
are retained under `source/references/`.

Native 3840 square RGBA masters, 1920 square HD derivatives and full native animation
remain separate GPU production jobs. Preview gallery cards are marked
`ready=false` and `finalReady=false` until reviewed deliveries exist.

Android `SymbolIcon` maps all 35 stock vector-name references to authored
bitmaps plus Codex/User role glyphs. `AnimatedMark` consumes a cached native frame
sheet, pauses out of foreground and obeys Android's reduced-motion setting.
`Modifier.pressMotion` scales drawing only, so squeezing does not move or shrink
the actual hit target. System notification monochrome requirements are preserved.

All scene preparation, renders, resampling and sprite packing run on registered
OZZZ hosts through guarded farm plans. Local work controls jobs and inspects
results. No third-party tutorial scene or stock icon path is used for original
NextComp assets.

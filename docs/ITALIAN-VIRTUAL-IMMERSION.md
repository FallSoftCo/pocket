# Contextual inline Italian immersion

The current requirement is contextual Italian phrases inside ordinary NextComp content. English remains the surrounding working content; chosen constituents become Italian at the same readable size. There is no permanent miniature English lane or duplicated whole-paragraph translation. The mode is optional per paired device/profile, off for other devices, and enabled for the owner's configured profile.

## Rendering contract

A dedicated, isolated, read-only stock Codex worker proposes contextual constituent replacements with exact quoted source anchors. The server resolves UTF-16 offsets, verifies the source hash and reconstructs the complete hybrid text before accepting it. Noun phrases include determiners and agreement; person, tense and word order changes require an appropriate clause. Density is manually adjustable between starter, balanced and strong; grammar takes priority over a numeric quota.

Original messages, commands, copied text, URLs, code, filenames, model identifiers, numeric literals, deadlines and question payloads stay intact. Protected ranges cannot be included in translated spans. Overlaps, partial words, broken grapheme boundaries, source-version mismatches and inconsistent complete text are rejected. These structural checks do not prove universal grammatical correctness.

Normal-size highlighted phrases open their English source and a short grammar note on demand. Control rescue uses tooltips, accessibility actions and keyboard assistance without taking ordinary taps or the Send long-press queue gesture. Compact session targets preserve their original tap behavior. Ambiguous repeated target phrases are not falsely aligned for phrase tapping; whole-source rescue remains available.

Speech uses the same accepted hybrid rendering, independently of temporary English rescue. Source text is preserved in the speech queue. Without an accepted rendering, source text remains readable and speakable. A previous accepted public update may remain visible while streaming, explicitly marked as updating and paired with its matching original rather than the newer source.

## Usage and bounds

The helper requires stock ChatGPT authentication and refuses API-key accounts. It receives only visible public text, never hidden raw reasoning or tool payloads. Same-source plans are reused within the same profile and density. Batches contain at most six ordinary sources or two when urgent work is present, globally prioritizing urgent sources, with a 16,000-character bound. Failed plans do not create an automatic retry loop. This adds Codex usage; it does not add a separately billed translation or audio API.

## Qualification and limitations

Synthetic stock CLI 0.160.0 / advertised gpt-6-luna qualification accepted contextual noun agreement, first-person progressive clauses and contracted prepositions. A two-source run took 14,425 ms and rejected its more complex protected example safely. A bounded protected retry took 5,844 ms and accepted “due nuove sessioni” and “una domanda importante” while preserving `notes.md` and gpt-6-luna. Receipts are in `qualification/inline-qualification.json` and `qualification/inline-protected-qualification.json`.

Debug33 emulator review at 2× font confirmed readable inline phrases and phrase-local full-size English/grammar help with protected literals retained. Final release runtime verification belongs to the release QA receipt. These examples are not native-speaker qualification of every possible sentence, guaranteed translation latency, word-timed audio alignment or a guarantee of mixed-language offline voice quality. Constituent teaching currently uses each submitted public text; it does not infer missing conversational antecedents.

The contextual-inline proposal supplied by the collaborating OZZZ agent and the maintained Mosis source-review package informed source preservation, truthful alignment and contextual assistance. Their separate media/API pipelines are not imported into NextComp.

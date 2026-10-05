# One-flow inline replacement immersion

The latest user correction replaces the earlier interlinear presentation: immersion uses the single existing text flow. Selected words or short phrases become Italian in place. No translated lines, parallel original/target text, subtitles, appended explanations or duplicated content are part of this mode. Partial mixed-language grammar is explicitly accepted; grammatical purity must not cause a full-sentence translation fallback.

## Accepted examples

Strong selection can replace substantial content while preserving context:

Original: I opened two new sessions and reviewed the important changes before lunch.

Rendered single flow: I opened due nuove sessioni and rivisto le modifiche importanti prima lunch.

Original: Please review the latest changes in `notes.md` using gpt-6-luna and https://example.com before lunch.

Rendered single flow: Please review le ultime modifiche in `notes.md` using gpt-6-luna and https://example.com prima lunch.

These examples describe alternative renderings for qualification, not simultaneous product lanes. The application shows one rendering at a time. Root-owned interaction assistance replaces content within that flow rather than appending another line.

## Selection and preservation

The isolated stock Codex teacher selects one to four words/short phrases per source, each containing at most six original lexical words. Density guides useful selection; there is no universal half-English ceiling. For ordinary content containing at least eight eligible lexical words, some unprotected original lexical context must remain. A complete sentence cannot be selected as one span inside that long content. Existing short labels may remain whole. Protected code, URLs, paths, filenames, model identifiers, numeric literals and action payloads retain their exact originals.

The server reconstructs the single rendered text from quoted, bounded source anchors. UTF-16 offsets, grapheme/contraction boundaries, source hashes, overlap, protected ranges and exact lexical target coverage remain validated. Optional contextual grammar metadata remains available as honest role/relationship evidence; its existence does not require a dual meaning renderer. These structural checks do not prove universal grammatical correctness.

## Cache and usage

The semantic plan revision is `inline-replacement-v3`. Earlier whole-line/interlinear-era plans are excluded from backend caches, and the Android integration uses the same revision. The mode remains optional per device/profile. Translation requires stock ChatGPT-authenticated Codex; no separately billed API is used. The bounded worker uses at most two ordinary sources or one urgent source per batch and has no automatic failure retry loop. Source fallback remains readable during delayed or rejected plans. Original outgoing input, copied commands and audit data are preserved.

## Qualification scope

Focused tests verify strong-density partial selection, permitted mixed grammar, rejection of whole-source/sentence fallback and long anchors, exact protected literals, source preservation and invalidation of earlier semantic-policy caches. The earlier contextual grammar stock receipt qualifies transport and the former grammar metadata schema; it does not qualify the revised teacher selection behavior. Any later actual teacher run must use synthetic data and the final revised prompt.

## Actual v3 teacher qualification

One isolated stock ChatGPT-authenticated Codex turn on advertised gpt-6-luna / medium effort returned three short replacements in 35,900 ms: “two new sessions” → “due nuove sessioni”; “reviewed the latest changes” → “ho esaminato le ultime modifiche”; “before lunch” → “prima di pranzo”. The final single flow keeps surrounding English and protected `notes.md` / gpt-6-luna exactly. The sanitized actual response is in `qualification/inline-replacement-v3-stock-codex.json`.

Initial validation rejected a grammar relation on “due”. Under the current optional-metadata contract, replay of that same recorded response retains its exact safe replacement and omits only that span's invalid teaching metadata; the other spans retain valid metadata. No guessed grammar, source change or second model request was used. This qualifies teacher transport/selection and final backend replay, not live phone rendering or guaranteed latency. Source, protected-range, overlap, short-span and partial-selection safeguards still reject unsafe replacements.

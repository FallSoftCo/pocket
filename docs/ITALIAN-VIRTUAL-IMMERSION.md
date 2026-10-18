# Italian grammatical immersion

The current product requirement is one reading flow cycling between **pure Italian** and **English vocabulary in the same Italian grammatical order**. It does not cycle back to ordinary English grammar, mix selected Italian vocabulary into ordinary English, add duplicate lines or open a help modal. Code, identifiers and protected literals retain their exact source. The original remains canonical for commands, submission, copying and audit.

The `transgrammatic-english-italian-v1` contract supplies complete Italian lexical segmentation and contextual English meanings. One validated plan supplies both forms. The Android viewport owns a continuous global reading-direction wipe; plans, parsing and text shaping are cached separately from the display clock. Generating another model answer on each language change is prohibited.

## Delivery architecture candidate (2026-10-17)

A versioned source job belongs to its authenticated device, environment, density and source hash. Pending jobs and attempts are persisted alongside that profile's validated plans. Two surfaces requesting the same content share inference and retain their own surface IDs. Only successfully validated sources leave the queue; missing/invalid results cannot be silently acknowledged as complete. Protected material, complete lexical coverage and grammatical alignment remain mandatory.

One bounded scheduler performs display-only inference. The first failure waits30 seconds; the second waits120 seconds; a third failed attempt becomes explicitly unavailable. Ordinary reopening/recomposition cannot reset this budget. A newer source is a new version. Jobs expire after15 minutes, are capped at120 per profile, and disabling immersion clears pending work. No session continuation, browser action or paid API fallback is admitted by this queue. Recovery after a restart is safe to repeat only because translation is display-only, not external action execution.

Android accepts socket notifications as accelerators. One profile-bound status observer reconciles pending jobs every2 seconds, backing off on transport failures; it never submits work or starts inference. Completed plans are cached by content and surface identity. Environment changes cancel the observer. Rejected or unavailable plans keep canonical content readable rather than inventing an English/Italian rendering.

The two-module workstation backend repair is deployed and the compatible alpha114/version132 APK is published through both existing updater channels. The phone was independently read as131 before installation; physical immersion is not yet verified. The versioned qualification receipt distinguishes actual synthetic provider inference, durable queue/restart checks, rendered native delivery and physical phone evidence. Admitted sources retain the existing12000character limit; safe protected-span segmentation for larger messages remains open. Older policies below are historical and do not define the current experience.

---

## Historical policies (superseded)

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

## Current reading wave

Current Android candidates cycle controls and content on one enrolled
visible-screen clock, with a cascading reading-direction wipe from global top
to bottom. Target-grammar support keeps vocabulary aligned with its Italian
counterpart. Literal names/IDs stay exact and cannot become template markup.
[Current rendering and perception evidence](IMMERSION-PERCEPTION-DESIGN.md)
distinguishes this implementation from historical prototypes. Cycling does not
call a model; semantic plans are cached separately.

The alpha92 candidate extends typed cycling to Team/Task/To/Agent selector
prefixes, direct recipient labels, reply clearing and authored event kinds.
[The actual native Team journey](qualification/team-control-coverage-20261012.json)
checks both languages, scoped drafts and an exact synthetic direct send.
Quoted reply bodies and agent status inside literal choices still need integration;
live team-provider and phone interaction are not qualified.

## Historical automatic inline cycling (alpha24)

Alpha24 adds automatic changes within the existing single text flow. Source and target phrases share reserved native phrase geometry, with a short fade, independent staggering and reading-length dwell; Italian stays visible longer. Tapping holds the original phrase and tapping again resumes cycling. Explicit displayed speech holds its captured flow, including after reopening the view. URLs, commands and code retain their original semantics. This does not introduce subtitles, duplicate lines or a modal.

The shorter form can leave reserved whitespace, especially when word counts differ. The [implementation and qualification boundary](qualification/automatic-inline-cycling.md) explains that typography tradeoff; native geometry and interaction checks must precede a delivery claim. Action labels and exact text-only session title rename targets retain their existing interaction.

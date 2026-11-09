# Grammar linkage and phrase boundaries

The prior lexical decoder changed every space in a lexical gloss into a hyphen.
That marked `to-the`, `the-latest` and `am-taking-care` regardless of their value
for learning Italian. It did not mark a relationship between distinct lexical
entries. The renderer then gave every complete phrase the same faint wash, making
its actual boundary hard to distinguish.

This candidate keeps three separate concepts:

- A lexical entry owns only its own meaning; multiword glosses retain spaces.
- A flip group contains the complete noun, prepositional or verb phrase, including
  articles, possessives, clitics and auxiliaries. Existing 0%/nearest-50% phases,
  cadence, native placeholders and browser grid reservations are unchanged.
- An explicit typed adjacent link marks a useful foreign dependency. A noun's
  post-nominal adjective can yield `The recordings-saved`; a preverbal clitic can
  yield `It-you can send`. An ordinary article/noun (`the file`), preposition
  (`to the station`), possessive (`The your music`), auxiliary (`I have finished`)
  or predicative adjective (`are ready`) remains spaced. Italian stays fluent.

The teacher supplies lexical roles and `linkPrevious`. The server rejects links
across groups or punctuation and role-incompatible links. A modifier chain must
start at a noun, not at an arbitrary adjacent adjective. The teacher must still
judge whether English already naturally uses that post-nominal construction;
this is not a claim of a universal deterministic grammar parser. Initial source
qualification used authored fixtures; the later actual teacher check is below.

Both renderers retain their measured shared-slot contract. The 183 reading
candidate replaces the always-visible 55% outline with a quiet 6% foreground wash
and 3px/dp corners. Ordinary control labels remain transparent; interactive text
has local touch/hover feedback and a keyboard-focus ring only on the focused
phrase. These treatments do not depend on the current language. Text stays at
full contrast. The existing 4px/dp horizontal and 1px/dp vertical padding, phrase
gutters and intrinsic reservations are unchanged, so removing the outline does
not move words or cause reflow. No animation, per-frame measurement or inference
is added. Equal pairs such as `Menu` retain no pointless toggle.

This follows a reading-highlight pattern rather than an outlined-input pattern.
The configured Mobbin MCP was queried and its actual screenshots inspected:
[Matter](https://mobbin.com/screens/058e54c1-2c6e-4568-939e-72ca0bd6ef85)
uses a soft filled passage highlight, while
[Duolingo ABC](https://mobbin.com/screens/6a7afff6-1193-4511-91de-129f1cc52007)
marks a word with fill instead of enclosing every word in a border. These are
visual references, not evidence of bilingual learning effectiveness. NextComp
uses its own existing palette and geometry; no reference assets are copied.

## Synthetic before/after

| Italian | Former mechanical support | Candidate support |
| --- | --- | --- |
| Le registrazioni salvate sono pronte | The recordings saved are ready | The recordings-saved are ready |
| Lo puoi inviare alla stazione | It you-can send to-the station | It-you can send to the station |
| Il tuo ultimo messaggio | The your latest message | The your latest message |
| Non ho ancora finito | Not I-have yet finished | Not I have yet finished |

These are authored test alignments, not claimed provider output. The unchanged
possessive example deliberately preserves Italian order without punctuation.

## Existing cache without regeneration

Old grouped plans discard the original lexical boundaries and roles. Therefore
this change does not guess new adjective/clitic links from cached prose or remove
all hyphens. It safely unwraps only a leading, exactly aligned Italian function
contraction and its matching English prefix:

| Target / old support | Candidate support |
| --- | --- |
| `alla stazione` / `to-the station` | `to the station` |
| `dell’account` / `of-the-account` | `of the account` |
| `l’ultimo messaggio` / `the-latest message` | `the latest message` |
| `l’accesso in sola lettura` / `the-read-only access` | `the read-only access` |
| `Non ho finito` / `Not I-have finished` | Unchanged: lexical provenance is missing |
| `Le registrazioni salvate` / `The recordings-saved` | Unchanged: useful link retained |

The migration changes only those gloss spaces; source text, Italian text,
offsets, identities, groups, genuine compounds, plan version and job budgets
remain. Target-prefix collisions (`allarme`), nonleading contractions and
English-prefix collisions (`of-theory`) are rejected. Existing quality checks
still reject duplicated meaning ownership. Valid plans remain cache hits:
there is no provider-wide regeneration or automatic retry-budget reset. The
existing backend worker applies this migration when loading its retained cache;
already materialized clients need the normal authenticated snapshot to receive
it. All existing paired groups benefit from the current reading treatment immediately
when their updated renderer is loaded.

## Qualification boundary

The focused Node tests exercise decoding, grouping, malformed links, duplicate
ownership, safe cached-prefix migration and zero-inference cache reuse. Authored
synthetic teacher output passes through production decode/validation and the
existing `web-phrase-immersion-check.mjs` journey renders actual ImmersionLayer
and phraseSlots. Browser checks cover light/dark at mobile/desktop widths, four
phases with exact fixed cell geometry, both-form reveal and honest identical
pairs. This is not a live teacher, Android compilation, native rendering, handset
performance, provider throughput or release qualification. Root owns those gates.

## Actual teacher check — 2026-11-08

One production-worker batch used five short synthetic inputs and the unchanged
normal model-selection policy (`gpt-6-luna`, medium). It completed in **22.67s**,
using **17,015 reported tokens** (16,019 input, 996 output), with no tools,
automatic retry, live-cache change or second generation. All five plans passed
structural validation. Original imperfect output was retained without correction.

| Synthetic input | Actual Italian | Actual English scaffold |
| --- | --- | --- |
| The saved recordings and the new file are ready. | Le registrazioni salvate e il file nuovo sono pronte. | The recordings-saved and the file-new are ready |
| You can send it to the station. | Puoi inviarlo alla stazione. | you can send it to the station |
| Your latest message is in this conversation. | Il tuo ultimo messaggio è in questa conversazione. | The your latest message is in this conversation |
| I have not yet finished the guide. | Non ho ancora finito la guida. | not I have yet finished the guide |
| Open the read-only account settings. | Apri le impostazioni dell’account read-only. | Open the settings of the account read-only |

The first example demonstrates useful `recordings-saved` and `file-new` links,
but its mixed-gender coordinated subject received **pronte**, where **pronti**
is expected. Structural validation therefore does not prove agreement correctness.
The second example chose the fluent enclitic **inviarlo** and correctly kept
`send it` together; it did **not** exercise the explicit preverbal
`cliticPredicate` link. The third and fourth retain ordinary article, possessive,
preposition and auxiliary spacing without duplicated meanings. The fifth retains
the genuine `read-only` compound, although its Italian uses that English technical
loanword rather than fully Italian vocabulary.

This bounded check demonstrates useful new links and ordinary spacing in actual
teacher output, **not universal linguistic correctness**. It adds no Android,
physical-device or sustained-provider qualification. No prompt tuning, model
change or rerun was performed after the imperfect agreement was observed.

## Delivery qualification

Android 180 / alpha162 was compiled, signed and published after both unit/lint
variants, 18 native journeys and an exact optimized catalog/history journey.
Native reading and narrow large-font footer checks retained the measured phrase
padding; reviewed screenshots show distinct phrase edges and spacing. The
workstation and Mac backend/web updates are live. These are software-emulator
and actual browser observations, not new physical-handset performance evidence.
One original emulator System UI ANR blocked admission before test interaction;
that failed attempt is retained separately from the passing successor.

The older grouped cache cannot recover discarded lexical roles. Only the safe
contraction-prefix repair applies to those records; new annotations use the
explicit link contract. No blanket retranslations were scheduled.

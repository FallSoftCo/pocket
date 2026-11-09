# Grammar linkage and phrase boundaries candidate

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
this is not a claim of a universal deterministic grammar parser. No new provider
call was made to qualify its linguistic adherence.

Both renderers retain their measured shared-slot contract and use a 1px/dp inset
edge, 6% foreground wash, 55% foreground edge and 3px/dp corners. Small 4px/dp
horizontal and 1px/dp vertical internal padding is included in both forms’
intrinsic reservation (explicitly included in Android placeholder dimensions). Source word spaces and
existing phrase gutters remain; borders do not wrap every lexical word or change
with the current language. No new animation, per-frame measurement or state loop
is introduced. Equal pairs such as `Menu` have no pointless web toggle, matching
the existing Android distinct-form behavior.

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
it. All existing paired groups benefit from the new visible edges immediately
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

# Rhyme View

Select a snippet (or type a phrase), and the canvas fills with rhyme and near-rhyme candidates that fit
the slot you are writing into. Supports a rhymes-first writing method: solve the rhyme scheme, then write
the flow around it.

Source brief: "Rhyme View — Implementation Brief" (Sep 22, 2026). This doc is the implementation contract
derived from it after reading the codebase. Where the two disagree, this doc wins and the reason is noted
under [Decisions](#decisions).

## Scope (v0)

- **Phonetic engine** (`backend/app/services/phonetics/`): pronunciation lookup (CMU dict, g2p_en fallback,
  user override), dialect map, rhyme keys, articulatory near-rhyme scoring, and an index over the dictionary
  and the user's own snippet lines. Pure module, no UI or DB dependencies.
- **Backfill**: every snippet gains a `phonetics` column; existing `grid_data` stress markers win over the
  engine and are stored as overrides; a report lists disagreements.
- **Suggest endpoint**: `POST /api/nlp/rhyme-suggest` returns ranked, slot-checked candidates from the
  dictionary and the user's notes.
- **Candidate layer** on the canvas: ephemeral candidate blocks rendered with the same beat-grid cells and
  stress marks as snippets, radial layout around the anchor, capped at 40 visible, promote to snippet,
  dismiss, discard all on exit.

## Non-scope (v0)

- Multi-word phrase rhymes (beam search or LLM), Datamuse, semantic axes, UMAP/blended layout, beat/BPM.
- Cell-level (single syllable) selection inside a snippet. Selection today is snippet-level only; v0 anchors
  are a whole snippet line's tail. A typed phrase covers the "tail from here" case.
- Persisted per-user controls panel (milestone 5). v0 controls live in the rhyme panel for the session.
- Any new workspace, group, or rename features.

## Vocabulary mapping

| Brief term | Codebase term |
|---|---|
| note | snippet (`Snippet` model, `SnippetCard`) |
| note line | one entry of `grid_data.lines` |
| workspace / song | canvas |
| stress markers | `GridCell.stress` (0/1/2) |
| syllable cell | `GridCell` |

## Data model

### Engine types (Python, `app/services/phonetics/types.py`)

All engine types are frozen dataclasses. Phones are ARPAbet strings with stress digits stripped.

```python
Stress = Literal[0, 1, 2]

class PronunciationSource(StrEnum):
    cmudict = "cmudict"
    g2p = "g2p"
    heuristic = "heuristic"   # vowel-group fallback if g2p is unavailable
    override = "override"

@dataclass(frozen=True, slots=True)
class Syllable:
    onset: tuple[str, ...]     # consonants before the nucleus
    nucleus: str               # exactly one vowel phone
    coda: tuple[str, ...]      # consonants after the nucleus
    stress: Stress
    deletable_coda: int = 0    # trailing coda phones the dialect map marks deletable (0 or 1)

@dataclass(frozen=True, slots=True)
class WordPronunciation:
    word: str                  # cleaned surface form
    syllables: tuple[Syllable, ...]
    source: PronunciationSource

@dataclass(frozen=True, slots=True)
class Pronunciation:
    words: tuple[WordPronunciation, ...]
    # .syllables -> flattened tuple[Syllable, ...]

RhymeKey = tuple[str, ...]     # dialect-mapped phones, stress stripped

class Provenance(StrEnum):
    note = "note"
    dictionary = "dictionary"
    datamuse = "datamuse"      # reserved, unused in v0
    generated = "generated"    # typed phrase anchors

@dataclass(frozen=True, slots=True)
class DerivedFrom:
    provenance: Provenance
    source_unit_id: str
    score: float
    match_depth: int
    k: int
    anchor_unit_id: str
    anchor_text: str

@dataclass(frozen=True, slots=True)
class Unit:
    id: str                    # "dict:<word>" | "note:<snippet_id>:<line>:<word_offset>" | "text:<sha1(text)>"
    text: str
    pronunciation: Pronunciation
    stress: str                # e.g. "1010", derived
    syllable_count: int        # derived
    rhyme_key: RhymeKey        # from last stressed nucleus to end, after dialect map
    rhyme_keys: Mapping[int, RhymeKey]   # k in 1..4 -> phones of the last k syllables (fewer if shorter)
    provenance: Provenance
    ephemeral: bool
    pronunciation_override: Pronunciation | None
    tags: tuple[str, ...]      # tag ids, notes only
    derived_from: DerivedFrom | None
    snippet_id: str | None
    line_index: int | None
    word_offset: int | None    # for note suffix units: first word included
```

Rules:

- `stress`, `syllable_count`, `rhyme_key`, `rhyme_keys` are derived from `pronunciation_override or
  pronunciation` plus the dialect map. `units.build_unit(...)` is the only constructor; nothing else sets
  derived fields.
- `pronunciation_override` wins over lookup. Backfill creates overrides where existing grid stress differs
  from the engine.
- Unit ids are deterministic so the frontend can keep candidate positions stable across re-queries.

### Dialect map (`dialect.py`)

```python
class RuleKind(StrEnum):
    merge = "merge"                  # replace phone sequence anywhere
    final_merge = "final_merge"      # replace only when the sequence ends a word
    optional_final = "optional_final"  # mark the final coda phone deletable when preceded by a consonant

@dataclass(frozen=True, slots=True)
class DialectRule:
    kind: RuleKind
    source: tuple[str, ...]
    target: tuple[str, ...]          # empty for optional_final

@dataclass(frozen=True, slots=True)
class DialectMap:
    rules: tuple[DialectRule, ...]
    def apply(self, pron: Pronunciation) -> Pronunciation
    def digest(self) -> str          # sha1 of the canonical rule list; cache key
```

Defaults shipped (`DEFAULT_DIALECT_MAP`), all user-editable in the request:

| Rule | Effect |
|---|---|
| `final_merge IH NG -> IH N` | -ing rhymes with -in |
| `optional_final T`, `optional_final D` | final T/D after a consonant may be dropped (test/mess, hand/man) |
| `merge ER -> AH R` | ER treated as AH + R |
| `merge AO -> AA` | cot-caught merger |

The map is applied after pronunciation and before any key or score. All derived fields are cached keyed by
`(text, override, dialect digest)`.

### Scoring (`distance.py`, `score.py`)

Articulatory feature vectors per ARPAbet phone. Vowels: height, backness, rounding, tenseness, diphthong
offglide. Consonants: place, manner, voicing. `phone_distance(a, b) -> float in [0, 1]`; vowel vs consonant
is distance 1. The initial vowel and consonant weights follow the Hirjee & Brown (2010) rap-lyrics
substitution matrix where it disagrees with the pure feature distance (e.g. AH/ER and IH/IY are closer than
the feature count suggests).

```python
@dataclass(frozen=True, slots=True)
class ScoreWeights:
    vowel: float = 1.0
    coda: float = 0.6
    onset: float = 0.25

DEPTH_WEIGHTS = (1.0, 0.7, 0.5, 0.35)   # final syllable first
MATCH_DEPTH_THRESHOLD = 0.8

@dataclass(frozen=True, slots=True)
class SyllableAlignment:
    a_index: int | None; b_index: int | None   # syllable indexes into each Pronunciation, None if missing
    nucleus: float; coda: float; onset: float; total: float

@dataclass(frozen=True, slots=True)
class RhymeScore:
    score: float            # [0, 1]
    match_depth: int        # contiguous syllables from the end with total >= threshold
    alignment: tuple[SyllableAlignment, ...]   # right-to-left

def score(a: Pronunciation, b: Pronunciation, k: int, weights: ScoreWeights = ...) -> RhymeScore
```

Algorithm: take the last `d = min(k, len(a), len(b))` syllables of each side, align right-to-left. Per
syllable: nucleus similarity `1 - phone_distance`; coda similarity by right-aligned phone pairing where an
unpaired phone costs 1 unless it is deletable (cost 0.25); onset similarity by left-aligned pairing.
Syllable total = weighted mean. Score = depth-weighted mean of totals. Equal rhyme keys at depth `d` score
exactly 1.0. Score for `d = 0` is 0.

### Index (`index.py`)

```python
@dataclass(frozen=True, slots=True)
class IndexHit:
    unit_id: str
    score: RhymeScore

class RhymeIndex:
    def __init__(self, dialect: DialectMap, weights: ScoreWeights = ...)
    def insert(self, unit: Unit) -> None
    def remove(self, unit_id: str) -> None
    def query(self, tail: Pronunciation, k: int, min_score: float, limit: int, exclude: frozenset[str] = ...) -> list[IndexHit]
    def __len__(self) -> int
```

Implementation is hash buckets, not a trie (see Decisions): for each unit and each `k` in 1..4, an exact
bucket keyed by `rhyme_keys[k]` and a skeleton bucket keyed by the last `k` nuclei. A query unions the exact
bucket, the skeleton bucket, and skeleton buckets for each nucleus's nearest vowel neighbours (distance ≤
0.35), then rescores every candidate with `score()` and drops those below `min_score`. Results sort by
score desc, match_depth desc, unit id asc.

### Sources (`sources/`)

```python
class CandidateSource(Protocol):
    name: Literal["notes", "dictionary"]
    def suggest(self, anchors: Sequence[Unit], k: int, min_score: float, limit: int, exclude: frozenset[str]) -> list[Candidate]
```

- `DictionarySource`: one process-wide `RhymeIndex` over CMU dict built lazily on first use. Excludes
  entries with digits or non-letter characters, pronunciation variants (`word(2)`), and, unless
  `include_rare`, words below Zipf frequency 3.0 via `wordfreq`.
- `NotesSource`: per-request index over the requesting user's snippet lines and their word-boundary
  suffixes (`note:<snippet_id>:<line>:<word_offset>` units), from `snippet.phonetics`. Ranked above
  dictionary hits at equal score.

Every candidate returned by a source has already been scored by the engine; sources never decide what
counts as a rhyme.

### Snippet storage

Migration `002_snippet_phonetics` adds two nullable JSONB columns to `snippets`:

- `phonetics`: `SnippetPhonetics` (pydantic, `app/schemas/phonetics.py`)
  ```
  {
    "dialect_digest": str,
    "lines": [
      {
        "line_index": int,
        "words": [ {"word": str, "source": str, "syllables": [ {"onset": [..], "nucleus": str, "coda": [..], "stress": 0|1|2} ]} ],
        "stress": "1010", "syllable_count": int,
        "rhyme_key": [..], "rhyme_keys": {"1": [..], "2": [..], "3": [..], "4": [..]}
      }
    ],
    "overrides": { "<line_index>:<word_index>": {"syllables": [..]} }
  }
  ```
- `derived_from`: `DerivedFrom` as JSON, set only by promotion.

`syllabify_text` keeps producing `grid_data`, but its per-word phones now come from `phonetics.pronounce`
(CMU, then g2p, then heuristic) so grid cells and phonetics never disagree for new text. The create and
update snippet routes compute both together.

Backfill (`backend/scripts/backfill_phonetics.py`): for every snippet with `phonetics IS NULL`, pronounce
each line; where `grid_data` stress for a word differs from the engine's, keep the grid stress, write an
override, and add a row to the report (`snippet_id, line, word, grid_stress, engine_stress`). Idempotent;
`--report path.csv`; `--dialect` optional.

## API contract

`POST /api/nlp/rhyme-suggest` (Bearer). Pydantic models in `app/schemas/rhyme.py`, TS mirrors in
`frontend/src/types/rhyme.ts`.

```jsonc
// request
{
  "canvas_id": "uuid",
  "anchors": [
    {"kind": "snippet", "snippet_id": "uuid", "line_index": 0},   // line_index null = last non-empty line
    {"kind": "text", "text": "door hinge"}
  ],
  "context": [ /* same shape; v0 uses their tag ids for tie-breaking only */ ],
  "slot": {"syllable_count": 2, "stress": "10"},                  // or null
  "k": 2,                       // 1..4
  "min_score": 0.5,
  "sources": ["notes", "dictionary"],
  "include_rare": false,
  "exclude_ids": ["dict:orange"],
  "limit": 40,                  // max 100
  "offset": 0,
  "weights": {"vowel": 1.0, "coda": 0.6, "onset": 0.25},          // or null
  "dialect": {"rules": [{"kind": "final_merge", "source": ["IH","NG"], "target": ["IH","N"]}]}  // or null
}
// response
{
  "anchors": [Unit],
  "candidates": [Candidate],
  "total": 312,
  "elapsed_ms": 41.2
}
```

```ts
interface RhymeSyllable { onset: string[]; nucleus: string; coda: string[]; stress: 0 | 1 | 2 }
interface RhymeUnit {
  id: string; text: string; provenance: 'note' | 'dictionary' | 'datamuse' | 'generated';
  ephemeral: boolean; syllable_count: number; stress: string; rhyme_key: string[];
  syllables: RhymeSyllable[]; grid_line: GridLine;           // grid_line renders with the existing cell code
  snippet_id?: string; line_index?: number; word_offset?: number; tags: string[];
}
interface SyllableAlignment { cand_index: number | null; anchor_index: number | null; nucleus: number; coda: number; onset: number; total: number }
interface RhymeCandidate extends RhymeUnit {
  score: number; match_depth: number; best_anchor_id: string; fits_slot: boolean;
  alignment: SyllableAlignment[];                            // right-to-left
  source: 'notes' | 'dictionary';
}
```

Semantics:

- A candidate must score ≥ `min_score` against at least one anchor. Ranked by best anchor score, then
  match depth, then notes before dictionary, then shared tag count with context, then id.
- `fits_slot` is true when the candidate's stress string has the slot's length and no candidate stressed
  syllable (1 or 2) lands on a slot `0`. Non-fitting candidates are returned, not hidden.
- Anchor units are echoed back with their computed phonetics so the UI can show the tail.
- Errors: 404 canvas not owned; 422 on invalid k, stress string, or empty anchors. Typed as
  `RhymeQueryError` in the engine, mapped in the route.
- Budget: dictionary query ≤ 200 ms warm, measured by a test.

Promotion is the existing `POST /api/canvases/{id}/snippets` with `raw_text`, `position_x/y`, and the new
optional `derived_from` field on `SnippetCreate`.

## Frontend design

Files under `frontend/src/`:

| File | Role |
|---|---|
| `types/rhyme.ts` | TS mirrors of the API contract |
| `api/client.ts` | `nlpApi.rhymeSuggest(req)`; `snippetApi.create` accepts `derived_from` |
| `stores/rhymeStore.ts` | Rhyme view mode, query params, candidates, dismissed ids, positions, loading/error |
| `lib/rhyme/radialLayout.ts` | Deterministic radial positions around the anchor; stable for existing ids |
| `lib/rhyme/slotFit.ts` | Slot derivation from a selected snippet line; client-side fit check for the panel |
| `lib/rhyme/queryFromSelection.ts` | Builds anchors from `selectedSnippetIds` + typed text |
| `components/canvas/gridDraw.ts` | Beat-grid drawing and cell text placement extracted from `SnippetCard` so both cards share it |
| `components/rhyme/CandidateCard.tsx` | Pixi block: provenance-coloured header, dashed border while ephemeral, grid cells, dimmed when `!fits_slot` |
| `components/rhyme/RhymeLayer.tsx` | Renders capped candidate cards inside the Pixi container above snippets |
| `components/rhyme/RhymePanel.tsx` | HTML overlay: typed anchor, k, min score, slot, sources, count, More, Exit |
| `components/rhyme/CandidateTooltip.tsx` | HTML overlay on hover: score, depth, aligned tail vs anchor, source, Promote/Dismiss |
| `components/rhyme/rhymeColors.ts` | Provenance colours and labels, non-fit alpha, alignment colour scale |
| `components/rhyme/dashedRect.ts` | Dashed rounded-rect stroke (Pixi v7 has no dashed line style) |
| `components/canvas/gridConstants.ts` | Cell/padding/header constants without Pixi imports (used by pure modules) |
| `lib/rhyme/candidateGeometry.ts` | Candidate card size and centre-to-top-left conversion, shared by the store and the card |
| `lib/rhyme/debounce.ts` | Debounced runner behind the 150 ms re-query hook |
| `lib/rhyme/errors.ts` | `toRhymeError`: axios failures to the typed `RhymeError` |
| `hooks/useRhymeQuery.ts` | Immediate query on enter; 150 ms debounced re-query on selection / anchor / param changes |

Store shape:

```ts
interface RhymeState {
  active: boolean;
  typedAnchor: string;
  k: 1 | 2 | 3 | 4; minScore: number;
  slot: Slot | null; slotMode: 'manual' | 'from-selection';
  sources: { notes: boolean; dictionary: boolean };
  anchors: RhymeUnit[]; candidates: RhymeCandidate[]; total: number; offset: number;
  positions: Map<string, { x: number; y: number }>;
  dismissed: Set<string>; hoveredId: string | null;
  loading: boolean; error: RhymeError | null;
  enter(): void; exit(): void; setTypedAnchor(t): void; setK(k): void; setMinScore(v): void;
  setSlot(s | null): void; toggleSource(name): void;
  runQuery(): Promise<void>;   // debounced 150 ms by the caller hook
  loadMore(): Promise<void>; dismiss(id): void; promote(id, at?): Promise<void>; setHovered(id | null): void;
  // additions (see Decisions): pinnedId, setPinned(id | null), setSlotMode(mode), viewSize, setViewSize(w, h)
}
```

Layout: `distance = base + (1 - score) * spread`, `angle = hash(rhyme_key.join(' ')) mod 2π` plus a small
per-id jitter so a rhyme family forms a wedge without overlapping. Existing ids keep their position across
re-queries; new ids are placed fresh. Anchor centre is the centroid of anchor snippets, or the viewport
centre for typed anchors.

Interactions:

- Toolbar button "Rhyme View" toggles `rhymeStore.active`. Entering with a selection queries immediately.
- Selection changes while active re-query after 150 ms.
- Hover a candidate: tooltip. Click: pin tooltip. Promote: button in tooltip or drag the card off the
  layer (drop anywhere on the canvas). Dismiss: button; id joins `dismissed` for the session.
- 40 visible; "More" appends the next 40 (offset paging).
- Exit: candidates, positions, and typed anchor cleared; snippets untouched.
- Promoted snippet appears at the drop position or the card's position, becomes a normal snippet, and the
  candidate is removed from the layer.

## Data / control flow

```
select snippet(s) or type phrase
  -> rhymeStore builds RhymeSuggestRequest (anchors, slot, k, min_score, sources, exclude=dismissed)
  -> POST /api/nlp/rhyme-suggest
       -> resolve anchors to Units (snippet.phonetics lines, or pronounce(text))
       -> NotesSource.suggest + DictionarySource.suggest (both via RhymeIndex.query, engine-scored)
       -> merge, dedupe by unit id, rank, slot-fit check, page
  <- anchors + candidates
  -> radialLayout (stable positions) -> RhymeLayer renders CandidateCards above snippets
  -> promote -> POST /api/canvases/{id}/snippets {raw_text, position, derived_from}
       -> syllabify_text + phonetics computed server-side -> snippet inserted in canvasStore
  -> exit -> layer cleared; nothing persisted except promoted snippets
```

## Invariants

- Only promotion writes anything durable. Candidates never touch `snippets` or the DB.
- Every candidate shown passed through `score()` and the slot check on the server.
- The user's snippets never move because of this view.
- `grid_data` stress and `phonetics` stress agree for every snippet; overrides encode any user-side
  disagreement so nothing the user marked changes.
- Derived Unit fields are recomputed whenever text, override, or dialect digest changes; the cache key
  includes all three.
- Hard cap of 100 candidates per response and 40 rendered per page.
- `k` in 1..4; stress strings match `^[012]+$`.

## Milestones and branches

| # | Milestone | Branch | Done when |
|---|---|---|---|
| 1 | Phonetic engine | `feat/rhyme-engine` | `pronounce`, `score`, `RhymeIndex` pass 50+ pairs incl. orange / door hinge under the default dialect map |
| 2 | Backfill | `feat/rhyme-engine` | migration + script; report of disagreements; syllabifier uses engine phones |
| 3 | Suggest endpoint | `feat/rhyme-engine` | ranked, fit-checked candidates for an anchor in < 200 ms (dictionary) |
| 4 | Candidate layer | `feat/rhyme-view` | enter, select, see capped radial candidates, promote, exit with nothing else changed |

`feat/rhyme-view` (frontend) is built against the contract above with mocked responses and needs
`feat/rhyme-engine` merged to function end to end. Both branches touch `docs/OVERVIEW.md` and this file.

## Decisions

- **Engine in Python on the backend**, not TypeScript. CMU dict, g2p, and the existing rhyme route are
  already there; the frontend does layout only.
- **Hash buckets instead of a reversed-phone trie.** A Python dict trie over ~130k CMU entries costs
  hundreds of MB; exact plus vowel-skeleton buckets give the same "everything ending in this tail" answer,
  fit the 200 ms budget, and the fuzzy walk is replaced by neighbour-vowel expansion plus full rescoring.
  The `RhymeIndex.query` interface is what the brief specifies, so a trie can be swapped in later.
- **No global unit table, no fork-on-edit.** Snippets are per-canvas already; promotion creates an ordinary
  snippet with `derived_from`. That field is also the promotion log the brief wants for ranking data.
- **Existing `find_rhymes` / `find_rhymes_in_texts` are replaced** by the engine under the same
  `GET /api/nlp/rhymes` route so the old sidebar keeps working. The old near-rhyme path matched only the
  final consonants (consonance), which the new scorer subsumes.
- **`wordfreq`** is added (offline, pinned) for the rare-word filter; CMU dict has no frequency or POS data.
- **g2p_en** is the OOV fallback. Its NLTK tagger data is fetched once at build time in the Dockerfile and
  by `phonetics.ensure_resources()`; if unavailable at runtime the heuristic path is used and the
  pronunciation source records it.
- **Default k = 2**, `min_score = 0.5`, default weights vowel 1.0 / coda 0.6 / onset 0.25, per the brief.
- **Cell-level selection deferred** (see Non-scope).
- **Datamuse and LLM deferred**; v0 is fully offline after install.
- **Frontend store additions (milestone 4).** `pinnedId`/`setPinned` back the "click pins tooltip"
  interaction; `setSlotMode` is needed to switch between from-selection and manual slots; `viewSize`/
  `setViewSize` let the store centre typed-anchor layouts on the viewport without knowing the DOM; `promote`
  takes an optional world top-left so a drag-drop can place the snippet where it landed. Nothing in the
  contract's fields or semantics changed.
- **Page window rendering.** `loadMore` appends pages to `candidates` (offset paging as specified) but the
  layer renders only `candidates.slice(offset, offset + 40)`, so at most 40 cards are on the canvas at once;
  the panel shows "Showing a–b of total".
- **Dismissed ids are cleared on exit** (session = one rhyme-view session), and a promoted id joins
  `dismissed` so a re-query does not bring the same unit back as a candidate next to its new snippet.
- **No anchors or no enabled source clears the layer client-side** instead of sending a request the server
  would reject with 422.
- **Manual slot validation** lives in the panel (`parseManualSlot`); invalid input sends `slot: null` and
  shows the reason rather than blocking the query.
- **`derived_from.anchor_text`** is taken from the echoed anchor unit whose id equals `best_anchor_id`
  (empty string if the server did not echo it).
- **Vitest 3.2.7** (exact pin) is the test runner; Vite 5 in this project is incompatible with vitest 4.

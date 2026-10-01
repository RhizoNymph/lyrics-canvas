# Rhyme View

Select a snippet (or type a phrase), and a sidebar lists rhyme and near-rhyme candidates that fit the slot
you are writing into; materialize the ones you want onto the canvas. Supports a rhymes-first writing method:
solve the rhyme scheme, then write the flow around it.

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
- **Rhyme sidebar**: a full-height right sidebar with the query inputs (search box, selected-snippet
  anchor chips, k, min score, slot, sources, rare words) and the ranked candidates as a list, 40 per page,
  each row with its beat-grid syllable strip, score, depth and fit; materialize a row to create a snippet on
  the canvas, dismiss to hide it; nothing reaches the canvas until materialized, and closing the sidebar
  discards the list.

## Non-scope (v0)

- Multi-word phrase rhymes (beam search or LLM), Datamuse, semantic axes, UMAP/blended layout, beat/BPM.
- Cell-level (single syllable) selection inside a snippet. Selection today is snippet-level only; v0 anchors
  are a whole snippet line's tail. A typed phrase covers the "tail from here" case.
- Persisted per-user controls panel (milestone 5). v0 controls live in the sidebar for the session.
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
    effective_pronunciation: Pronunciation   # derived: dialect applied to override-or-pronunciation
    dialect_digest: str                      # derived: the map the derived fields were computed under
```

Rules:

- `effective_pronunciation`, `dialect_digest`, `stress`, `syllable_count`, `rhyme_key`, `rhyme_keys` are
  derived from `pronunciation_override or pronunciation` plus the dialect map. `units.build_unit(...)` is
  the only constructor; nothing else sets derived fields. `rhyme_keys[k]` starts at the nucleus of the
  k-th syllable from the end (that syllable's onset is not part of the key), so equal keys mean a
  perfect rhyme at that depth; `rhyme_key` starts at the nucleus of the last primary-stressed syllable
  (else the last secondary, else the last syllable).
- `pronunciation_override` wins over lookup. Backfill creates overrides where existing grid stress differs
  from the engine.
- Unit ids are deterministic so the frontend can dedupe pages and track dismissed / materialized units across re-queries.

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
| `optional_final T`, `optional_final D` | word-final T/D after a consonant may be dropped (test/mess, hand/man); marks `deletable_coda` on every word's last syllable |
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
unpaired phone costs 1 unless it is deletable (cost 0.25); onset similarity by left-aligned pairing, not
counted for the deepest aligned syllable (the rhyme starts at its nucleus, so cat / bat is perfect).
Syllable total = weighted mean. Score = depth-weighted sum of totals divided by the weight mass of
`min(k, max(len(a), len(b)))` syllables, so a side shorter than the requested depth leaves its missing
syllables at zero (hinge / door hinge at k=2 is 1.0 / 1.7 and ranks below orange). Equal rhyme keys at that
full depth score exactly 1.0. Score for `d = 0` is 0. `score(a, b)` equals `score(b, a)`.

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

Implementation is hash buckets, not a trie (see Decisions): for each unit and each `d` in 1..4, an exact
bucket keyed by `rhyme_keys[d]` (holding the member ids and one representative tail) and, per exact key,
skeleton buckets keyed by its last `j` nuclei for `j` in 1..d. A query, for each depth `d` up to
`min(k, len(tail))`, picks the smallest `j` whose depth-weight mass can still reach `min_score`
(`j = 1` for k=2 at 0.5, `j = 2` for k=3..4), unions the skeleton buckets for every combination of the
tail's last `j` nuclei and their vowel neighbours (distance ≤ 0.35), scores each distinct exact key once
with `score()` (all members of a key share the same tail, hence the same score), and drops keys below
`min_score`. Units shorter than `k` are scored at their own depth. Results sort by score desc,
match_depth desc, unit id asc; candidate-side alignment indexes are rebased onto the real unit.
`insert` raises `RhymeIndexError` for a unit built under another dialect; `query` accepts a per-call
`weights` override so one shared index serves requests with custom weights.

### Sources (`sources/`)

```python
class CandidateSource(Protocol):
    name: Literal["notes", "dictionary"]
    def suggest(self, anchors: Sequence[Unit], k: int, min_score: float, limit: int, exclude: frozenset[str]) -> list[Candidate]
```

- `DictionarySource`: one process-wide `RhymeIndex` per `(dialect digest, include_rare)` over CMU dict,
  built lazily on first use (about 2 s, 27k units for the common set) and kept in a 4-entry LRU. Excludes
  entries with digits or non-letter characters other than internal apostrophes (don't, talkin'),
  pronunciation variants (`word(2)`), and, unless `include_rare`, words below Zipf frequency 3.0 via
  `wordfreq`.
- `NotesSource`: per-request index over the requesting user's snippet lines and their word-boundary
  suffixes (`note:<snippet_id>:<line>:<word_offset>` units), from `snippet.phonetics` (computed on the fly
  when a snippet has none yet), skipping the anchor lines. Ranked above dictionary hits at equal score;
  the service keeps one suffix per line (best score, depth, fits the slot, then the longest).

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
  `words[].syllables` is the stored base pronunciation (the override where one exists, else the lookup,
  dialect not applied); `stress`, `rhyme_key`, `rhyme_keys` are derived under `dialect_digest`. Overrides
  carry the grid's stress on the engine's phones; when the syllable counts differ the engine's syllables are
  right-aligned to the grid cells and missing ones are filled with a stressless AH. Unpronounceable tokens
  keep their word index with an empty syllable list. Rest cells are ignored.
- `derived_from`: `DerivedFrom` as JSON, set only by materializing a candidate.

`syllabify_text` keeps producing `grid_data`, but its per-word phones now come from `phonetics.pronounce`
(CMU, then g2p, then heuristic) so grid cells and phonetics never disagree for new text. The create and
update snippet routes compute both together.

Backfill (`backend/scripts/backfill_phonetics.py`, `uv run python -m scripts.backfill_phonetics`): for
every snippet with `phonetics IS NULL`, pronounce each line; where `grid_data` stress for a word differs
from the engine's, keep the grid stress, write an override, and add a row to the report (`snippet_id,
line, word, grid_stress, engine_stress`). Idempotent; `--report path.csv` (stdout if omitted);
`--dialect rules.json` optional; `--force` recomputes rows that already have phonetics. Commits in
batches of 200 through the app's async session. `app/services/phonetics_service.py` holds the pure logic
(`compute_snippet_phonetics`, `compute_snippet_phonetics_with_report`, `reconcile`,
`note_units_from_phonetics`). On snippet update, a grid-only edit recomputes phonetics from the stored
raw text with the existing overrides as the base; a text change recomputes from scratch.

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
  match depth, then notes before dictionary, then shared tag count with context, then id. Candidates whose
  text equals an anchor's text, the anchor units themselves, and `exclude_ids` are never returned.
  `total` is the number of ranked candidates (each source contributes at most 5000).
- `fits_slot` is true when the candidate's stress string has the slot's length and no candidate stressed
  syllable (1 or 2) lands on a slot `0`. Non-fitting candidates are returned, not hidden.
- Anchor units are echoed back with their computed phonetics so the UI can show the tail and each anchor's
  rhyme key.
- Errors: 404 canvas not owned or anchor snippet not owned (`CanvasNotFoundError`,
  `SnippetNotFoundError` in `rhyme_suggest_service`); 422 on invalid k, stress string, limit, or empty
  anchors (pydantic), on a bad line index, an empty snippet or an unpronounceable text anchor
  (`RhymeQueryError`), and on a malformed dialect rule (`DialectRuleError`). Mapped in the route.
- Budget: dictionary query ≤ 200 ms warm, measured by a test.

Materializing (the brief's "promote") is the existing `POST /api/canvases/{id}/snippets` with `raw_text`,
`position_x/y`, and the new optional `derived_from` field on `SnippetCreate`.

## Backend files

| File | Role |
|---|---|
| `backend/app/services/phonetics/` | Engine package: `types`, `errors`, `distance`, `syllables`, `dialect`, `score`, `index`, `pronounce`, `resources`, `units`, `sources/{base,dictionary,notes}` (see [docs/backend/nlp-phonetics.md](../backend/nlp-phonetics.md)) |
| `backend/app/schemas/phonetics.py` | `SnippetPhonetics`, `LinePhonetics`, `WordPhonetics`, `SyllableModel`, `PronunciationOverride`, `DerivedFromModel` |
| `backend/app/schemas/rhyme.py` | `RhymeSuggestRequest` / `RhymeSuggestResponse` and the unit, candidate, slot, weights and dialect models |
| `backend/app/services/phonetics_service.py` | `compute_snippet_phonetics(_with_report)`, `reconcile`, `note_unit(s_from_phonetics)`, model conversions |
| `backend/app/services/rhyme_suggest_service.py` | `suggest`, `SnippetRepository` protocol + `DbSnippetRepository`, anchor resolution, `rank`, `fits_slot`, `dedupe_note_suffixes`, response models |
| `backend/app/services/nlp/syllabifier.py` | Beat grid from engine phones |
| `backend/app/services/nlp/rhyme_service.py` | Legacy `find_rhymes` / `find_rhymes_in_texts` on the engine |
| `backend/app/api/nlp.py` | `POST /api/nlp/rhyme-suggest`, `GET /api/nlp/rhymes` |
| `backend/app/api/snippets.py` | Create/update compute `phonetics`; create accepts `derived_from` |
| `backend/app/models/snippet.py`, `backend/alembic/versions/002_snippet_phonetics.py` | `phonetics`, `derived_from` columns |
| `backend/app/config.py` | `Settings.nltk_data_dir` (default `backend/.nltk_data`) |
| `backend/scripts/backfill_phonetics.py` | Backfill + disagreement report |
| `backend/tests/phonetics/`, `backend/tests/test_*.py`, `backend/tests/fakes.py` | Engine, service, route and backfill tests |

## Frontend design

The frontend is a sidebar, not a canvas layer (see Decisions, 2026-09-30). Files under `frontend/src/`:

| File | Role |
|---|---|
| `types/rhyme.ts` | TS mirrors of the API contract; `RhymeError` (`operation: 'query' \| 'materialize'`) |
| `api/client.ts` | `nlpApi.rhymeSuggest(req)`; `snippetApi.create` accepts `derived_from` |
| `stores/rhymeStore.ts` | Session (`active`), query inputs, anchors, candidates, dismissed ids, chip exclusions, materialize counts, loading/error; `materialize` is the only path to the canvas |
| `lib/rhyme/materializePosition.ts` | Where a materialized snippet lands: right of the single anchor (+40 px), stepping down 60 px per earlier materialization from it; else the viewport centre |
| `lib/rhyme/candidateList.ts` | List rows (dismissed excluded, limited to the loaded pages), "Showing a–b of total", More enablement |
| `lib/rhyme/geometry.ts` | `Point`, `Viewport`, `ViewSize`, `viewportCenter` |
| `lib/rhyme/slotFit.ts` | Slot derivation from a selected snippet line; client-side fit check; manual slot validation |
| `lib/rhyme/queryFromSelection.ts` | Builds anchors from `selectedSnippetIds` minus chip exclusions + typed text |
| `lib/rhyme/debounce.ts` | Debounced runner behind the 150 ms re-query hook |
| `lib/rhyme/errors.ts` | `toRhymeError`: axios failures to the typed `RhymeError` |
| `hooks/useRhymeQuery.ts` | 150 ms debounced re-query on selection / anchor / chip / param changes while open |
| `components/sidebar/RhymeSidebar.tsx` | 380 px right sidebar; `enter()` on mount, `exit()` on unmount |
| `components/rhyme/QueryControls.tsx`, `AnchorChips.tsx` | Search box, anchor chips with echoed rhyme keys (× removes for this query), k, min score, slot, sources, rare words |
| `components/rhyme/CandidateList.tsx`, `CandidateRow.tsx`, `SyllableStrip.tsx`, `AlignmentTable.tsx` | Status line + More, rows (text, syllable strip, score, depth, fit badge, source colour), expand to the aligned tail, Materialize / Dismiss |
| `components/rhyme/rhymeColors.ts`, `sidebarStyles.ts` | Provenance colours and labels, non-fit alpha, alignment colour scale; sidebar styles |
| `components/canvas/gridDraw.ts`, `gridConstants.ts` | Beat-grid drawing and constants used by `SnippetCard` (unchanged by this feature) |

Store shape:

```ts
interface RhymeState {
  active: boolean;                       // sidebar open
  typedAnchor: string; excludedAnchorIds: Set<string>;
  k: 1 | 2 | 3 | 4; minScore: number;
  slot: Slot | null; slotMode: 'manual' | 'from-selection';
  sources: { notes: boolean; dictionary: boolean }; includeRare: boolean;
  anchors: RhymeUnit[]; candidates: RhymeCandidate[]; total: number; offset: number;
  dismissed: Set<string>; materializeCounts: Map<string, number>;
  viewSize: { width: number; height: number };
  loading: boolean; error: RhymeError | null;
  enter(): void;                         // activates and queries immediately
  exit(): void; setTypedAnchor(t): void; excludeAnchor(id): void; restoreAnchor(id): void;
  setK(k): void; setMinScore(v): void; setSlot(s | null): void; setSlotMode(m): void;
  toggleSource(name): void; setIncludeRare(v): void; setViewSize(w, h): void;
  runQuery(): Promise<void>;             // debounced 150 ms by the caller hook
  loadMore(): Promise<void>; dismiss(id): void; materialize(id): Promise<void>;
}
```

Interactions:

- Toolbar button "Rhymes" toggles `uiStore.rhymeSidebarOpen`; the sidebar mounts, calls `enter()`, and
  queries immediately when a snippet is selected or a phrase is typed.
- Selection, typed anchor, chip, and parameter changes re-query after 150 ms; Enter in the search box queries now.
- A chip's × removes that selected snippet from the anchors for this query (restored by + or by deselecting
  and reselecting it).
- Click a candidate's text to make it the typed anchor (chain search). Click a row to expand the aligned tail
  against the anchor.
- Materialize: a snippet is created to the right of the single anchor snippet (stepping down per
  materialization) or at the viewport centre; the row is removed and its id joins `dismissed`. The selection
  is unchanged so the next search keeps the same anchor.
- Dismiss: row removed; id joins `dismissed` for the session.
- 40 per page; "More" appends the next 40 (offset paging); "Showing a–b of total".
- Close: list, typed anchor, exclusions and dismissed ids cleared; snippets untouched.

## Data / control flow

```
select snippet(s) or type phrase
  -> rhymeStore builds RhymeSuggestRequest (anchors, slot, k, min_score, sources, exclude=dismissed)
  -> POST /api/nlp/rhyme-suggest
       -> resolve anchors to Units (snippet.phonetics lines, or pronounce(text))
       -> NotesSource.suggest + DictionarySource.suggest (both via RhymeIndex.query, engine-scored)
       -> merge, dedupe by unit id, rank, slot-fit check, page
  <- anchors + candidates
  -> sidebar list (rows; nothing on the canvas)
  -> materialize -> materializePosition -> POST /api/canvases/{id}/snippets {raw_text, position, derived_from}
       -> syllabify_text + phonetics computed server-side -> snippet inserted in canvasStore
  -> close -> list cleared; nothing persisted except materialized snippets
```

## Invariants

- Only materializing writes anything durable. Candidates never touch `snippets` or the DB; nothing is drawn on
  the canvas until a row is materialized.
- Every candidate shown passed through `score()` and the slot check on the server.
- The user's snippets never move because of this feature.
- `grid_data` stress and `phonetics` stress agree for every snippet; overrides encode any user-side
  disagreement so nothing the user marked changes.
- Derived Unit fields are recomputed whenever text, override, or dialect digest changes; the cache key
  includes all three.
- Hard cap of 100 candidates per response and 40 listed per page.
- `k` in 1..4; stress strings match `^[012]+$`.

## Milestones and branches

| # | Milestone | Branch | Done when |
|---|---|---|---|
| 1 | Phonetic engine | `feat/rhyme-engine` | `pronounce`, `score`, `RhymeIndex` pass 50+ pairs incl. orange / door hinge under the default dialect map |
| 2 | Backfill | `feat/rhyme-engine` | migration + script; report of disagreements; syllabifier uses engine phones |
| 3 | Suggest endpoint | `feat/rhyme-engine` | ranked, fit-checked candidates for an anchor in < 200 ms (dictionary) |
| 4 | Candidate layer | `feat/rhyme-view` | enter, select, see capped radial candidates, promote, exit with nothing else changed |
| 4b | Rhyme sidebar | `feat/rhyme-sidebar` | replaces 4: open the sidebar, select or type, see the list, materialize, close with nothing else changed |

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
- **Rhyme keys and scoring start at the nucleus of the deepest syllable.** With the onset counted, cat / bat
  could not score 1.0 and "equal keys score 1.0" would contradict "perfect rhyme == 1.0". Inner syllables
  keep their onsets in both keys and scores.
- **Missing syllables discount the score.** The brief's depth-weighted mean over `d = min(k, len(a),
  len(b))` let every "-inge" monosyllable outrank orange for "door hinge" at k=2. The mean now runs over the
  weight mass of `min(k, max(len(a), len(b)))` syllables; equal keys still score exactly 1.0 when both sides
  reach that depth, and the score stays symmetric.
- **Skeleton lookups use the last `j` nuclei, with `j` chosen from `min_score`.** A full-depth skeleton
  missed candidates whose final syllables match but whose deeper nucleus is far (syringe for door hinge);
  matching only the last `j` syllables can still reach `min_score` when the weight mass of `j` syllables
  does, so those buckets are searched too. Keys are scored once per bucket, not once per member, which is
  what keeps common tails inside the 200 ms budget (measured 1-115 ms warm across k=1..4).
- **`Unit` carries `effective_pronunciation` and `dialect_digest`.** Both are derived; storing them lets the
  index and sources score without re-applying the dialect and lets `RhymeIndex.insert` reject units built
  under another map (`RhymeIndexError`, a fourth engine error alongside `ResourceError` for NLTK data).
- **`optional_final` marks every word's final syllable**, not only the phrase's last word: t/d deletion is a
  word-final process and the deletable flag only matters where a coda is compared.
- **Dictionary entries may contain internal apostrophes** (don't, talkin'), which lyric vocabulary needs;
  digits, symbols and `word(2)` variants are still excluded.
- **NLTK data is fetched directly** (httpx, zips from the nltk_data pages tree) rather than via
  `nltk.download`, which refuses proxied fetches and group-writable directories; the layout is the one
  `nltk.data.find` expects, and `Settings.nltk_data_dir` (default `backend/.nltk_data`, ignored by git) is
  registered at startup and populated in the Docker build.
- **One note suffix per line in the response.** All suffixes of a line share a tail and a score; the
  service keeps the one that fits the slot, else the longest, so the list is not flooded with near-copies.
- **Legacy `GET /api/nlp/rhymes`**: "perfect" is a 1.0 engine score over the query's rhyme-key depth,
  "near" anything else at or above 0.5 (dictionary) or 0.7 (snippets); the response shape is unchanged.
- **Index hits are scored at the requested depth.** `RhymeIndex.query` scores each bucket's
  representative tail with `score(anchor, tail, k)`, not at the bucket depth `d`, so a one-syllable
  member takes the same short-side discount a direct `score()` call applies (hinge / door hinge at k=2
  is 0.59 from both paths). Before this fix the live endpoint ranked monosyllabic perfect rhymes at 1.0
  above every two-syllable match at k=2.
- **Cell-level selection deferred** (see Non-scope).
- **Datamuse and LLM deferred**; v0 is fully offline after install.
- **Sidebar list instead of a canvas layer (2026-09-30).** User decision: nothing reaches the canvas until
  materialized. Milestone 4's radial candidate layer, Pixi candidate cards, hover tooltip, drag-to-promote
  and the separate "Rhyme View" toggle are removed; the engine now backs the right sidebar (replacing the old
  word-lookup sidebar behind the "Rhymes" button). Results are a scrollable list with the same per-row data
  (syllable strip, score, depth, fit, source, aligned tail on expand) and two actions, Materialize and
  Dismiss. Materialize places the snippet beside the single anchor snippet (right edge + 40, stepping down 60
  per earlier materialization from that anchor) or at the viewport centre, and does not change the selection
  so the next search keeps its anchor. Store fields that only served the layer (`positions`, `pinnedId`,
  `hoveredId`) are gone; `viewSize` stays because the viewport-centre fallback needs it. The API contract is
  unchanged.
- **Anchor chips with per-query exclusion.** Each selected snippet is a chip showing its echoed rhyme key; ×
  removes it from the anchors (and from slot derivation) while it stays selected, so a multi-select can be
  narrowed without touching the canvas selection.
- **`setSlotMode`** switches between from-selection and manual slots; **`includeRare`** exposes the request's
  `include_rare` flag.
- **Pages append.** `loadMore` appends pages to `candidates` (offset paging as specified); the list shows every
  loaded row minus dismissed ones (`listRows`), with "Showing a–b of total".
- **Dismissed ids are cleared on close** (session = one sidebar session), and a materialized id joins
  `dismissed` so a re-query does not bring the same unit back as a candidate next to its new snippet.
- **No anchors or no enabled source clears the list client-side** instead of sending a request the server
  would reject with 422.
- **Manual slot validation** lives in the sidebar (`parseManualSlot`); invalid input sends `slot: null` and
  shows the reason rather than blocking the query.
- **`derived_from.anchor_text`** is taken from the echoed anchor unit whose id equals `best_anchor_id`
  (empty string if the server did not echo it).
- **Vitest 3.2.7** (exact pin) is the test runner; Vite 5 in this project is incompatible with vitest 4.

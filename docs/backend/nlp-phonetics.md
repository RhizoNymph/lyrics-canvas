# NLP: Phonetic Engine

Pronunciation lookup, dialect mapping, articulatory near-rhyme scoring, and a rhyme index over
the CMU dictionary and the user's own snippet lines. Pure module: no UI or DB dependencies.

**Package:** `app/services/phonetics/`
**Feature doc:** [docs/features/rhyme_view.md](../features/rhyme_view.md) (the contract)

## Files

| File | Role | Key exports |
|---|---|---|
| `types.py` | Frozen engine types | `Syllable`, `WordPronunciation`, `Pronunciation`, `Unit`, `DerivedFrom`, `PronunciationSource`, `Provenance`, `RhymeKey` |
| `errors.py` | Exception hierarchy | `PhoneticsError` > `PronunciationError`, `DialectRuleError`, `RhymeQueryError`, `RhymeIndexError`, `ResourceError` |
| `distance.py` | Feature vectors and phone distance | `phone_distance(a, b)`, `vowel_neighbours(v)`, `VOWELS`, `CONSONANTS`, `PHONES`, `is_vowel` |
| `syllables.py` | ARPAbet parsing, maximal-onset syllabification | `parse_phones`, `syllabify_phones`, `flatten`, `LEGAL_ONSETS` |
| `dialect.py` | Dialect rules and map | `DialectMap`, `DialectRule`, `RuleKind`, `DEFAULT_DIALECT_MAP` |
| `score.py` | Right-to-left syllable alignment scoring | `score(a, b, k, weights)`, `ScoreWeights`, `RhymeScore`, `SyllableAlignment`, `DEPTH_WEIGHTS`, `MATCH_DEPTH_THRESHOLD` |
| `index.py` | Hash-bucket rhyme index | `RhymeIndex`, `IndexHit`, `min_skeleton_depth` |
| `pronounce.py` | CMU / g2p_en / heuristic lookup with caching | `pronounce(text)`, `pronounce_word(word)`, `clean_word`, `heuristic_pronunciation`, `g2p_available` |
| `resources.py` | NLTK data for g2p_en | `ensure_resources(data_dir=None)`, `configure(data_dir)`, `default_data_dir()` |
| `units.py` | The only `Unit` constructor and id helpers | `build_unit(...)`, `unit_from_text`, `dict_unit_id`, `note_unit_id`, `text_unit_id`, `tail_key`, `rhyme_key_depth` |
| `sources/base.py` | Source protocol and shared query helper | `Candidate`, `CandidateSource`, `SourceName`, `suggest_from_index` |
| `sources/dictionary.py` | Process-wide CMU index (lazy, LRU by dialect) | `DictionarySource`, `build_dictionary_index`, `common_words`, `RARE_ZIPF_THRESHOLD` |
| `sources/notes.py` | Per-request index over note units | `NotesSource` |
| `__init__.py` | Public surface | `pronounce`, `score`, `RhymeIndex`, `DEFAULT_DIALECT_MAP`, `build_unit`, `ensure_resources`, all types and errors |

## Pronunciation

```
pronounce(text) -> Pronunciation(words=(WordPronunciation, ...))
  text.split() -> for each token: pronounce_word(token)   # lru_cache(131072)
    clean_word: lowercase, letters and apostrophes only; empty -> skipped
    1. pronouncing.phones_for_word(clean)[0]   -> source cmudict
    2. g2p_en.G2p()(clean)                     -> source g2p       (lazy; needs NLTK data)
    3. heuristic_pronunciation(clean)          -> source heuristic (vowel groups, first stressed)
  parse_phones (strip stress digits, validate against PHONES) -> syllabify_phones
```

Syllabification follows the maximal onset principle: between two vowels the longest legal
English onset (`LEGAL_ONSETS`: singletons except NG, stop/fricative + liquid/glide, S-clusters,
S + stop + liquid) goes to the next syllable, the rest to the coda. Word-initial consonants all
join the first onset; word-final consonants all join the last coda.

`ensure_resources()` fetches `taggers/averaged_perceptron_tagger_eng`, `corpora/cmudict` and
(optional, presence check only) `taggers/averaged_perceptron_tagger` as zips from the nltk_data
GitHub pages tree with httpx, unpacks them next to the zip in a `0o700` directory, and puts that
directory first on `nltk.data.path`. The directory comes from `configure()` (set from
`Settings.nltk_data_dir` at app startup), else `NLTK_DATA_DIR` / `NLTK_DATA`, else
`backend/.nltk_data`. The Dockerfile runs it at build time. If it fails (`ResourceError`) the
g2p loader marks itself failed and the heuristic path is used; the source marker records which
path produced each word.

## Dialect map

Rules rewrite phones after pronunciation and before any key or score. Applied per word on the
flattened phone sequence, then re-syllabified; stress is carried to the target vowel.

| Kind | Effect | Validation |
|---|---|---|
| `merge` | replace the source sequence anywhere in a word | target non-empty, at most one vowel in source, same vowel count |
| `final_merge` | replace only when the sequence ends the word | same |
| `optional_final` | mark the word-final coda phone deletable when preceded by a consonant (`Syllable.deletable_coda = 1`) | exactly one consonant, no target |

Defaults (`DEFAULT_DIALECT_MAP`): `final_merge IH NG -> IH N`, `optional_final T`,
`optional_final D`, `merge ER -> AH R`, `merge AO -> AA`. `digest()` is the sha1 of the
canonical JSON rule list and keys every cache and index.

## Distance

Vowels: height, backness, rounding, tenseness, diphthong offglide (+ offglide direction).
Consonants: place (8 ordinal classes), manner (6 classes with a graded manner-pair table),
voicing. Weighted sums capped at 1; vowel vs consonant is 1. Overrides for AH/ER 0.2, IH/IY
0.25, AH/IH 0.3, AA/AO 0.15, AH/UH 0.35 follow the Hirjee & Brown (2010) substitution weights.
`vowel_neighbours(v)` is every vowel within 0.35 (inclusive of `v`), used by the index.

## Scoring

```
score(a, b, k, weights=ScoreWeights(vowel=1.0, coda=0.6, onset=0.25)) -> RhymeScore
  d = min(k, len(a), len(b)) syllables aligned right-to-left
  per syllable: nucleus = 1 - phone_distance
                coda    = right-aligned pairing; unpaired phone costs 1, dropping a deletable
                          final phone costs 0.25; best over drop variants
                onset   = left-aligned pairing (ignored for the deepest aligned syllable)
                total   = weighted mean
  score = sum(DEPTH_WEIGHTS[i] * total_i) / sum(DEPTH_WEIGHTS[:min(k, max(len(a), len(b)))])
  match_depth = contiguous syllables from the end with total >= 0.8
```

Equal rhyme keys at full depth score exactly 1.0 (cat/bat, station/nation). Missing syllables
on the shorter side count as zero: hinge vs door hinge at k=2 is 1.0/1.7. `d == 0` scores 0.
Symmetric in `a` and `b`; `alignment[i].a_index/b_index` are syllable indexes into each side.

## Units

`build_unit` is the only constructor. From `pronunciation_override or pronunciation` and the
dialect it derives (cached by `(pronunciation, dialect)`, 262k entries):

- `effective_pronunciation` (dialect applied), `dialect_digest`
- `stress` ("1010"), `syllable_count`
- `rhyme_key`: phones from the nucleus of the last primary-stressed syllable (else last
  secondary, else the last syllable) to the end
- `rhyme_keys[d]` for `d` in 1..min(4, n): phones from the nucleus of the `d`-th syllable from
  the end (its onset excluded) to the end

Ids: `dict:<word>`, `note:<snippet_id>:<line>:<word_offset>`, `text:<sha1(normalised text)>`.

## Index

`RhymeIndex(dialect, weights)` refuses units built under another dialect (`RhymeIndexError`).

- For each unit and depth `d` in 1..4: an exact bucket keyed by `rhyme_keys[d]` holding the
  member ids plus one representative tail (the last `d` syllables, deepest onset stripped).
- For each exact key at depth `d`: skeleton buckets keyed by its last `j` nuclei, `j` in 1..d.
- `query(tail, k, min_score, limit, exclude, weights=None)`: apply the dialect to the tail,
  `k_eff = min(k, len(tail))`; for each `d` in 1..k_eff take `j = min_skeleton_depth(d,
  min_score)` (the smallest `j` whose depth-weight mass reaches `min_score`), union the
  skeleton buckets for every neighbour-vowel variant of the tail's last `j` nuclei, score each
  distinct key once against its representative tail, keep members (units with exactly `d`
  syllables for `d < k_eff`, at least `k_eff` for `d == k_eff`) scoring >= `min_score`, sort by
  score desc, match depth desc, id asc, cut to `limit`, and rebase candidate-side alignment
  indexes onto the real unit.

Members of a key bucket share the same tail and therefore the same score, so a query scores a
few thousand keys at most. Warm dictionary queries measured on the 27k common-word index:
1-115 ms across k=1..4 with a 5000-hit limit (`tests/phonetics/test_dictionary_timing.py`
asserts < 200 ms); the index builds in about 2 s on first use.

## Sources

- `DictionarySource(dialect, weights, include_rare)`: `index()` returns the process-wide
  `RhymeIndex` for `(dialect digest, include_rare)` from a 4-entry LRU registry built under a
  lock. Entries: CMU words matching `^[a-z]+('[a-z]+)*'?$` (no variants like `word(2)`, no
  digits or symbols), first pronunciation only; without `include_rare` only words at Zipf
  frequency >= 3.0 (`wordfreq`, offline).
- `NotesSource(units, dialect, weights)`: a per-request index over the caller's units.
- Both use `suggest_from_index`: query once per anchor, keep the best anchor per unit, rank,
  cap at `limit`. Every candidate carries the engine's `RhymeScore`; sources never decide what
  counts as a rhyme.

## Tests

`backend/tests/phonetics/`: 50+ scored pairs (`pairs.py`), syllabification, dialect
application and digest, distance ordering, pronunciation fallbacks, score symmetry/bounds/
depth, unit derivation, index insert/query/remove/exclude/limit, and the dictionary timing
budget (marker `timing`). Run with `uv run pytest tests/phonetics`.

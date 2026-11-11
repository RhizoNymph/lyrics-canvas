# NLP: Syllabifier

Beat-grid syllabification. Per-word phones and stress come from the
[phonetic engine](./nlp-phonetics.md) (`phonetics.pronounce_word`: CMU dict, then g2p_en, then a
letter heuristic); this module only maps syllables back onto the word's letters.

**File:** `app/services/nlp/syllabifier.py`

## API

```python
def syllabify_text(text: str) -> dict      # Full text -> beat grid
def syllabify_word(word: str) -> list[dict] # Single word -> syllable list
```

## Algorithm

```
Input word -> pronounce_word(word)        (engine: clean, CMU dict -> g2p_en -> heuristic)
  -> WordPronunciation.syllables          (maximal-onset syllables with stress 0/1/2)
  -> _split_word_text(clean, n):
       Map the syllable count back onto the cleaned word's letters
       Track vowel groups (a,e,i,o,u,y) to find split points
       Split after consonants following vowels
  -> [{"syllable": text, "stress": stress}, ...]
```

Words that clean to nothing (numbers, symbols) yield no cells but still occupy a `word_index`.

**Rest marks.** Each `_` in a line is a rest and also separates words
([rest_marks.md](../features/rest_marks.md)): it becomes the cell `{"rest": true}` (no other keys) in
token order, `word_index` counts word tokens only, and `syllable_count` excludes rests. A line of only
rests is kept (a line of rest cells, count 0); `raw_text` stays the trimmed line with its marks.
Tokenizing is `rest_marks.tokenize_line`.
Because the same `pronounce_word` feeds `snippets.phonetics`, grid stress and phonetics stress
agree for every newly syllabified text (`tests/test_phonetics_service.py` checks this); user
edits to grid stress are preserved as phonetics overrides (see
[nlp-phonetics](./nlp-phonetics.md) and `app/services/phonetics_service.py`).

### Output Structure

```python
{
  "lines": [
    {
      "cells": [
        {"syllable": "hel", "stress": 1, "word_index": 0},
        {"syllable": "lo", "stress": 0, "word_index": 0},
        {"syllable": "world", "stress": 1, "word_index": 1}
      ],
      "syllable_count": 3,
      "raw_text": "hello world"
    },
    {
      "cells": [
        {"syllable": "go", "stress": 1, "word_index": 0},
        {"rest": true},
        {"syllable": "now", "stress": 1, "word_index": 1}
      ],
      "syllable_count": 2,
      "raw_text": "go_now"
    }
  ]
}
```

## Text Processing Flow

```
syllabify_text(text):
  Split text by newlines
  For each non-blank (trimmed) line:
    tokenize_line: "_" -> rest token (also a separator), whitespace-separated words
    For each token:
      rest -> {"rest": true}
      word -> syllabify_word(word) -> list of {syllable, stress}, tagged with the word's index
              (word tokens only; rests take no index)
    Collect cells, count syllables, preserve raw_text
  Return {lines: [...]}
```

## Stress Values

| Value | Meaning | Display |
|-------|---------|---------|
| 0 | Unstressed | Gray text |
| 1 | Primary stress | Bold white text |
| 2 | Secondary stress | Bold white text |

## Other consumers

- Voice takes ([voice_capture.md](../features/voice_capture.md)): `syllabify_text` builds the grid of a
  new voice-take snippet and of the lines appended to an existing one (existing lines are never
  re-syllabified); `syllabify_word` gives the auto split its per-word syllable counts, so line balance
  matches the grid.

## Route

```
POST /api/nlp/syllabify  (no auth required)
Body: { "text": "hello world" }
Response: SyllabifyResponse { "lines": [GridLineModel, ...] }   (cells carry only the keys the syllabifier set)
```

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
    }
  ]
}
```

## Text Processing Flow

```
syllabify_text(text):
  Split text by newlines
  For each line:
    Split by spaces into words
    For each word:
      syllabify_word(word) -> list of {syllable, stress}
      Track word_index for each cell
    Collect cells, count syllables, preserve raw_text
  Return {lines: [...]}
```

## Stress Values

| Value | Meaning | Display |
|-------|---------|---------|
| 0 | Unstressed | Gray text |
| 1 | Primary stress | Bold white text |
| 2 | Secondary stress | Bold white text |

## Route

```
POST /api/nlp/syllabify  (no auth required)
Body: { "text": "hello world" }
Response: SyllabifyResponse { "lines": [GridLineModel, ...] }   (cells carry only the keys the syllabifier set)
```

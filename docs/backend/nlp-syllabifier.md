# NLP: Syllabifier

CMU Pronouncing Dictionary-based syllabification with heuristic fallback.

**File:** `app/services/nlp/syllabifier.py`

## API

```python
def syllabify_text(text: str) -> dict      # Full text -> beat grid
def syllabify_word(word: str) -> list[dict] # Single word -> syllable list
```

## Algorithm

### Primary Path: CMU Dictionary

```
Input word -> clean (lowercase, letters/apostrophe only)
  -> pronouncing.phones_for_word(word)
    -> phoneme string (e.g., "R AE1 D IY0 OW0")
      -> _cmu_syllabify():
          Split on spaces, identify vowel phonemes (contain digits 0-2)
          Group consonants around vowels into syllables
          Extract stress from vowel digit (0=unstressed, 1=primary, 2=secondary)
      -> _split_word_text():
          Map phoneme syllable count back to original text
          Track vowel groups (a,e,i,o,u,y) to find split points
          Split after consonants following vowels
```

### Fallback Path: Heuristic

When a word is not in the CMU dictionary:

```
Input word -> count vowel groups (aeiouy)
  -> 0 vowels: return word as single stressed syllable
  -> N vowels: split proportionally, stress first syllable
```

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
Response: { "lines": [...] }
```

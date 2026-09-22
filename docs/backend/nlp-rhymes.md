# NLP: Rhyme Finder

Phoneme-based rhyme detection using the CMU Pronouncing Dictionary plus in-corpus matching.

**File:** `app/services/nlp/rhyme_service.py`

## API

```python
def find_rhymes(word: str, include_near: bool = True) -> dict
def find_rhymes_in_texts(word: str, texts: dict[str, str]) -> list[dict]
```

## Dictionary Rhymes

### Perfect Rhymes

```
word -> pronouncing.rhymes(word)
     -> up to 50 results (sorted alphabetically)
```

Uses the CMU dictionary's built-in rhyme detection: words that share the same phoneme sequence from the last stressed vowel onward.

### Near Rhymes

```
word -> pronouncing.phones_for_word(word)
     -> find last vowel phoneme (contains digit 0-2)
     -> extract rhyming part: phonemes from last vowel forward
     -> pronouncing.search(rhyming_part + "$")  # regex end anchor
     -> filter out: perfect rhymes, the query word itself
     -> up to 30 results
```

Near rhymes match only the ending vowel+consonant pattern, which produces partial phoneme matches (assonance, slant rhymes).

### Output

```python
{
  "perfect": ["cat", "bat", "hat", ...],
  "near": ["cot", "rot", "lot", ...]
}
```

## Snippet Rhymes

Finds rhyming words within the user's existing lyrics.

```
word -> get perfect rhymes from dictionary
     -> tokenize all snippet texts (lowercase, strip punctuation)
     -> set intersection: words_in_text & rhyme_set
     -> for each match, record snippet context
```

### Output

```python
[
  {
    "word": "cat",
    "rhyme_type": "perfect",
    "source": "snippet",
    "snippet_id": "uuid-...",
    "snippet_preview": "first 100 chars of snippet text"
  }
]
```

## Route

```
GET /api/nlp/rhymes?word=<word>  (requires auth)
```

Combines both dictionary and snippet searches:

1. `find_rhymes(word)` -> dictionary perfect + near rhymes
2. Fetch all non-empty snippets across user's canvases
3. Build corpus: `{snippet_id: raw_text}`
4. `find_rhymes_in_texts(word, corpus)` -> snippet matches

### Response

```json
{
  "dictionary": { "perfect": [...], "near": [...] },
  "snippets": [{ "word": "...", "rhyme_type": "...", ... }]
}
```

## Limits

| Category | Max Results |
|----------|-------------|
| Perfect rhymes (dictionary) | 50 |
| Near rhymes (dictionary) | 30 |
| Snippet rhymes | Unlimited (all matches) |

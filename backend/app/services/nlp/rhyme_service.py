"""Legacy rhyme lookup for GET /api/nlp/rhymes, backed by the phonetic engine.

"Perfect" means the engine scores 1.0 over the query's rhyme-key depth (last stressed nucleus
to the end, after the default dialect map); "near" is anything else scoring at or above
NEAR_THRESHOLD at that depth, best first.
"""

from __future__ import annotations

from functools import lru_cache

from app.services.phonetics import DEFAULT_DIALECT_MAP
from app.services.phonetics.errors import PronunciationError
from app.services.phonetics.pronounce import clean_word
from app.services.phonetics.score import MAX_K, score
from app.services.phonetics.sources.dictionary import DictionarySource
from app.services.phonetics.types import Unit
from app.services.phonetics.units import rhyme_key_depth, unit_from_text

PERFECT_LIMIT = 50
NEAR_LIMIT = 30
NEAR_THRESHOLD = 0.5
SNIPPET_NEAR_THRESHOLD = 0.7
QUERY_LIMIT = 5000


@lru_cache(maxsize=4096)
def _word_unit(word: str) -> Unit | None:
    clean = clean_word(word)
    if not clean:
        return None
    try:
        return unit_from_text(clean, DEFAULT_DIALECT_MAP)
    except PronunciationError:
        return None


def _depth(unit: Unit) -> int:
    return min(MAX_K, rhyme_key_depth(unit.effective_pronunciation.syllables))


def find_rhymes(word: str, include_near: bool = True) -> dict:
    """Perfect and near rhymes from the dictionary index: {"perfect": [...], "near": [...]}."""
    query = _word_unit(word)
    if query is None:
        return {"perfect": [], "near": []}
    index = DictionarySource().index()
    hits = index.query(
        query.pronunciation, k=_depth(query), min_score=NEAR_THRESHOLD if include_near else 1.0, limit=QUERY_LIMIT
    )
    perfect: list[str] = []
    near: list[str] = []
    for hit in hits:
        unit = index.get(hit.unit_id)
        if unit is None or unit.text == query.text:
            continue
        if hit.score.score == 1.0:
            perfect.append(unit.text)
        elif include_near:
            near.append(unit.text)
    return {"perfect": sorted(perfect)[:PERFECT_LIMIT], "near": near[:NEAR_LIMIT]}


def find_rhymes_in_texts(word: str, texts: dict[str, str]) -> list[dict]:
    """Rhyming words inside the user's snippets. texts: {snippet_id: raw_text}."""
    query = _word_unit(word)
    if query is None:
        return []
    depth = _depth(query)
    results: list[dict] = []
    for snippet_id, text in texts.items():
        seen: set[str] = set()
        for token in text.split():
            unit = _word_unit(token)
            if unit is None or unit.text in seen or unit.text == query.text:
                continue
            seen.add(unit.text)
            result = score(query.effective_pronunciation, unit.effective_pronunciation, depth)
            if result.score == 1.0:
                rhyme_type = "perfect"
            elif result.score >= SNIPPET_NEAR_THRESHOLD:
                rhyme_type = "near"
            else:
                continue
            results.append(
                {
                    "word": unit.text,
                    "rhyme_type": rhyme_type,
                    "source": "snippet",
                    "snippet_id": snippet_id,
                    "snippet_preview": text[:100],
                }
            )
    results.sort(key=lambda r: (r["rhyme_type"] != "perfect", r["snippet_id"], r["word"]))
    return results

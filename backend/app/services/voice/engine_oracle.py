"""The phonetic engine behind the auto split's syllable counts and end-rhyme scores."""

from __future__ import annotations

from functools import lru_cache

from app.services.nlp.syllabifier import syllabify_word
from app.services.phonetics import DEFAULT_DIALECT_MAP, Pronunciation, pronounce_word, score
from app.services.phonetics.dialect import DialectMap

# Depth of the end-rhyme comparison: the last two syllables, like a feminine rhyme check; a
# one-syllable word compares at depth one.
RHYME_DEPTH = 2


@lru_cache(maxsize=65536)
def _syllables(word: str) -> int:
    return len(syllabify_word(word))


@lru_cache(maxsize=65536)
def _pronunciation(word: str, dialect: DialectMap) -> Pronunciation | None:
    pron = pronounce_word(word)
    if pron is None or not pron.syllables:
        return None
    return dialect.apply(Pronunciation(words=(pron,)))


class EngineOracle:
    """Syllables as the beat grid counts them; rhyme = engine near-rhyme score, dialect applied."""

    def __init__(self, dialect: DialectMap = DEFAULT_DIALECT_MAP):
        self.dialect = dialect

    def syllables(self, word: str) -> int:
        return _syllables(word)

    def end_rhyme(self, a: str, b: str) -> float:
        pa = _pronunciation(a, self.dialect)
        pb = _pronunciation(b, self.dialect)
        if pa is None or pb is None:
            return 0.0
        return score(pa, pb, RHYME_DEPTH).score
